(ns bacure.read-properties
  (:refer-clojure :exclude [send])
  (:require [bacure.coerce :as c]
            [bacure.local-device :as ld]
            [bacure.remote-device :as rd]
            [clojure.walk :as walk]))

(import (com.serotonin.bacnet4j 
          service.confirmed.ReadPropertyRequest
          service.confirmed.ReadPropertyMultipleRequest
          type.constructed.ReadAccessSpecification
          type.enumerated.AbortReason
          type.enumerated.ErrorCode
          util.PropertyValues
          util.PropertyReferences
          exception.AbortAPDUException
          exception.ErrorAPDUException))

(def ^:dynamic *nil-on-APDU-exception*
  "Return nil instead of throwing an exception." true)

(defn send-request
  "Send the request to the remote device"
  [device-id request]
  (.send @ld/local-device (rd/rd device-id) request))


;; ================================================================
;; =====================  Normal read request =====================
;; ================================================================

(defn read-property-request
  "Create a read-property request.

  [:analog-input 0] :description
  [:analog-input 0] [:description 0] <--- with array index"
  [object-identifier property-reference]
  (let [prop-ref (c/c-property-reference property-reference)]
    (ReadPropertyRequest.
     (c/c-object-identifier object-identifier)
     (.getPropertyIdentifier prop-ref)
     (.getPropertyArrayIndex prop-ref))))

(defn read-single-property
  "Read a single property."
  [device-id object-identifier property-reference]
  (->> (read-property-request object-identifier property-reference)
       (send-request device-id)
       (.getValue)
       c/bacnet->clojure))

(defn size-related? [apdu-exception]
  (some #{(.getAbortReason (.getApdu apdu-exception))}
        [(.intValue AbortReason/bufferOverflow)
         (.intValue AbortReason/segmentationNotSupported)]))

(defn partition-array
  "Ask the remote device what is the length of the BACnet array and
  return as many object-property-identifiers. If object is not an
  array, return nil." [device-id object-identifier property-reference]
  (try
    (let [size (read-single-property device-id object-identifier
                                     [property-reference 0])] ;; 0 return the array length
      (into []
            (for [index (range 1 (inc size))]
              [object-identifier [property-reference index]])))
    (catch ErrorAPDUException e)))

(defn read-array-individually
  "Read a BACnet array one time at the time and re-assemble the result
  afterward." [device-id object-identifier property-reference]
  (mapv (partial apply (partial read-single-property device-id))
        (partition-array device-id object-identifier property-reference)))
      
(defn read-single-property-with-fallback
  "Read a single property. If there's a size-related APDU error, will
  try to read the BACnet arrays one item at the time."
  [device-id object-identifier property-reference]
  (try (read-single-property device-id object-identifier property-reference)
       (catch AbortAPDUException e
         (if (size-related? e)
           (read-array-individually device-id object-identifier property-reference)
           (throw e)))))

  
(defn read-individually
  "Given a list of object-property-references, return a map or
  properties.

  [[[:analog-input 0] :description]
   [[:analog-input 1] :object-name]
   [[:device 1234] [:object-list 2]]] <---- with array index

  ---->

  ({:description \"ANALOG INPUT 0\", :object-identifier [:analog-input 0]}
   {:object-name \"ANALOG INPUT 1\", :object-identifier [:analog-input 1]}
   {[:object-list 2] [:analog-input 0], :object-identifier [:device 1234]})"
  [device-id object-property-references]
  (->> (for [[oid prop-ref] object-property-references]
         (let [result (try (read-single-property-with-fallback
                             device-id oid prop-ref)
                           (catch ErrorAPDUException e
                             (when-not *nil-on-APDU-exception*
                               (throw e))))]
           (into {} [[prop-ref result]
                     [:object-identifier oid]])))
       (group-by :object-identifier)
       vals
       (map (partial apply merge))))


;; ================================================================
;; ==================  And now read property multiple =============
;; ================================================================

(defn find-max-refs [device-id]
  (if (some #{(rd/segmentation-supported device-id)} [:both :transmit])
    (.getMaxReadMultipleReferencesSegmented @ld/local-device)
    (.getMaxReadMultipleReferencesNonsegmented @ld/local-device)))


(defn partition-object-property-references
  [device-id obj-prop-references]
  (let [max-refs (find-max-refs device-id)]
    (partition-all max-refs obj-prop-references)))
  
(defn read-property-multiple-request
  "Create a read-property-multiple request.
   [[object-identifier property-references]
    [object-identifier property-references]]"
  [obj-prop-references]
  (ReadPropertyMultipleRequest.
   (c/c-array (partial apply c/c-read-access-specification)
              obj-prop-references)))

(defn BACnet-array?
  "Return true if the raw data returned by a read property is part of
  an array."[data]
  (->> (dissoc data :object-identifier) keys first coll?))

(defn assemble-arrays
  "Reassemble arrays from data returned from a
  read-property-multiple." [data]
  (let [sort-fn (fn [x] (sort-by #(first (keys (dissoc % :object-identifier))) x))
        grouped-by-properties
        (group-by (juxt :object-identifier
                        (comp ffirst keys #(dissoc % :object-identifier))) data)]
    ;; group-by --> [[:device 1234] :object-list] --> [<object-identifier> <property-type>]
    (for [values grouped-by-properties]
      (let [object-identifier (ffirst values)
            property-type (second (first values))]
        (->> (second values)
             (map #(first (vals (dissoc % :object-identifier))))
             ((fn [x] (if (> (count x) 1) x (first x))))
             ((fn [x] {:object-identifier object-identifier
                       property-type x})))))))

(defn read-property-multiple*
  "read-access-specification should be of the form:
   [[object-identifier property-references]
    [object-identifier property-references]]"
  [device-id obj-prop-references]
  (->> (for [refs (partition-object-property-references
                   device-id obj-prop-references)]
         (->> (read-property-multiple-request refs)
              (send-request device-id)
              (.getListOfReadAccessResults)
              (mapcat c/bacnet->clojure)))
       (apply concat)
       (group-by BACnet-array?)
       ((fn [x] (concat (assemble-arrays (get x true)) (get x false))))
       (group-by :object-identifier)
       vals
       (map (partial apply merge))))



(defn split-opr [obj-prop-references]
  (split-at (/ (count obj-prop-references) 2) obj-prop-references))

(declare read-property-multiple)

(defn read-array-in-chunks
  "Read the partitioned arrays in chunks and then assemble them back
  together." [device-id partitioned-array]
  (->> (for [opr (split-opr partitioned-array)]
         (read-property-multiple device-id opr))
       (apply concat)
       (apply (fn [x y] (let [oid (find x :object-identifier)]
                          (apply (partial merge-with concat)
                                 (map #(dissoc % :object-identifier) [x y])))))))
  

(defn read-property-multiple
  "read-access-specification should be of the form:
   [[object-identifier property-references]
    [object-identifier property-references]]

   In case of an APDU size problem, will divide the request in two
   parts. If any of those two parts have a problem, it will be split
   again, and so on and so forth.

   If the object-property-references is a single item and there's
   still an APDU size problem, it probably means the property we are
   trying to read is an array. It will be divided and read in chunks."
  [device-id obj-prop-references]
  (->> (try (read-property-multiple* device-id obj-prop-references)
            (catch Exception e
              (cond
               (not *nil-on-APDU-exception*) (throw e)
               (> (count obj-prop-references) 1) :obj-prop-ref-size-problem
               
               (not (coll? ((comp first next first) obj-prop-references))) ;;not already an array index
               {:partitioned-array (apply (partial partition-array device-id)
                                          (first obj-prop-references))})))
       ((fn [x] (cond (= x :obj-prop-ref-size-problem)
                      (->> (for [opr (split-opr obj-prop-references)]
                             (read-property-multiple device-id opr))
                           (apply concat)
                           (remove nil?))
                      (map? x)
                      (read-array-in-chunks device-id (:partitioned-array x))
                      :else x)))))

;; ================================================================
;; ===========  Abstract the differences between the two ==========
;; ================================================================

(defn replace-special-identifier
  "For devices that don't support the special identifiers 
   (i.e. :all, :required and :optional), return a list of properties.

   The :all won't be as the one defined by the BACnet standard,
   because we can't know for sure what are the properties. (Especially
   in the case of proprietary objects." [object-property-references]
   (for [single-object-property-references object-property-references]
     (let [[object-identifier & properties] single-object-property-references
           f (fn [x] (if (#{:all :required :optional} x)
                       (c/properties-by-option (first object-identifier) x) [x]))]
       (cons object-identifier
             (distinct (for [prop properties new-prop (f prop)] new-prop))))))

(defn expand-obj-prop-ref
  "Take a normal object-property-references, such as

   [ [[:analog-input 0] :description :object-name] ...]

   and separate the properties into individual references to obtain:
   [ [[:analog-input 0] :description]
     [[:analog-input 0] :object-name] ...]"
  [obj-prop-refs]
  (->> (for [[oid & prop-refs] obj-prop-refs]
         (map (fn [x] [oid x]) prop-refs))
       (apply concat)))

(defn read-properties
  "Retrieve the property values form a remote device.
   Format for object-property-references should be:

   [ [[:analog-input 0] :description :object-name]   <--- multiple properties
     [[:device 1234] [:object-list 0]                <--- array access
     [[:analog-ouput 1] :present-value]  ...]"
  [device-id object-property-references]
  (if (:read-property-multiple (rd/services-supported device-id))
    (read-property-multiple device-id object-property-references)
    (->> object-property-references
         replace-special-identifier
         expand-obj-prop-ref
         (read-individually device-id))))

(defn read-properties-multiple-objects
  "A convenience function to retrieve properties for multiple
  objects." [device-id object-identifiers properties]
  (->> (for [oid object-identifiers] (cons oid properties))
       vector
       (apply (partial read-properties device-id))))

