(ns bacure.read-properties
  (:refer-clojure :exclude [send])
  (:require [bacure.coerce :as c]
            [bacure.coerce.obj :as co]
            [bacure.coerce.service.acknowledgement]
            [bacure.local-device :as ld]
            [bacure.state :as state]
            [bacure.events :as events]
            [bacure.services :as services]))

(import (com.serotonin.bacnet4j service.confirmed.ReadPropertyRequest
                                service.confirmed.ReadPropertyMultipleRequest
                                type.constructed.ReadAccessSpecification
                                type.enumerated.AbortReason
                                type.enumerated.ErrorCode
                                util.PropertyValues
                                util.PropertyReferences
                                exception.AbortAPDUException
                                exception.ErrorAPDUException
                                exception.ServiceTooBigException))

;; ================================================================
;; =====================  Normal read request =====================
;; ================================================================

(defn read-property-request
  "Create a read-property request.

  [:analog-input 0] :description
  [:analog-input 0] [:description 0] <--- with array index"
  [object-identifier property-reference]
  (let [prop-ref (c/clojure->bacnet :property-reference property-reference)]
    (ReadPropertyRequest.
     (c/clojure->bacnet :object-identifier object-identifier)
     (.getPropertyIdentifier prop-ref)
     (.getPropertyArrayIndex prop-ref))))

(defn read-single-property
  "Read a single property."
  [local-device-id device-id object-identifier property-reference]
  (->> (read-property-request object-identifier property-reference)
       (services/send-request-promise local-device-id device-id)))

(defn expand-array
  "Ask the remote device what is the length of the BACnet array and
  return as many object-property-identifiers. If object is not an
  array (or any other error), return nil.

  An array length can be provided to avoid a request to a remote device."
  ([local-device-id device-id object-identifier property-reference]
   (let [read-result (read-single-property local-device-id
                                           device-id
                                           object-identifier
                                           [property-reference 0])]
     (if-let [size (:success read-result)]
       (expand-array local-device-id device-id object-identifier property-reference size)
       (do (println (str "Couldn't retrieve array length : " read-result))
           nil))))
  ([local-device-id device-id object-identifier property-reference size]
   (into []
         (for [index (range 1 (inc size))]
           [object-identifier [property-reference index]]))))

(defn read-array-individually
  "Read a BACnet array one time at the time and re-assemble the result
  afterward." [local-device-id device-id object-identifier property-reference]
  (mapv (partial apply (partial read-single-property local-device-id device-id))
        (expand-array local-device-id device-id object-identifier property-reference)))

(defn size-related?
  "True if the abort reason is size related."
  [abort-map]
  (some #{(or (:abort-reason abort-map)
              (:reject-reason abort-map))}
        [:segmentation-not-supported :buffer-overflow]))

(defn read-single-property-with-fallback
  "Read a single property. If there's a size-related APDU error, will
  try to read the BACnet arrays one item at the time."
  [local-device-id device-id object-identifier property-reference]

  (try
    (let [read-result (read-single-property local-device-id device-id object-identifier property-reference)]
      (cond
        (:success read-result) read-result

        ;;;;;
        (or (:abort read-result)
            (:reject read-result))

        (if (size-related? (or (:abort read-result) (:reject read-result)))
          (mapv (fn [result]
                  (or (get result :success)
                      (println (str "Read array error for " object-identifier " - " property-reference
                                    "\n result : " result))))
                (read-array-individually local-device-id device-id object-identifier property-reference))
          (throw (or (some-> read-result :abort :apdu-error)
                     (Exception. "APDU abort"))))
        ;;;;;
        (:timeout read-result) (throw (or (some-> read-result :timeout :timeout-error)
                                          (Exception. "Timeout")))
        :else read-result))))

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


(defn read-individually
  "Given a list of object-property-references, return a list of object properties maps.

  [[[:analog-input 0] :description]
   [[:analog-input 1] :object-name]
   [[:device 1234] [:object-list 2]]] <---- with array index

  ---->

  ({:description \"ANALOG INPUT 0\", :object-identifier [:analog-input 0]}
   {:object-name \"ANALOG INPUT 1\", :object-identifier [:analog-input 1]}
   {[:object-list 2] [:analog-input 0], :object-identifier [:device 1234]})"
  [local-device-id device-id object-property-references]
  (->> (for [[oid prop-ref] object-property-references]
         (let [read-result (read-single-property-with-fallback
                            local-device-id device-id oid prop-ref)]
           (into {} [[prop-ref (if-let [result (:success read-result)]
                                 result ;; if success, just return the result
                                 read-result)]
                     [:object-identifier oid]])))
       (group-by BACnet-array?)
       ((fn [x] (concat (assemble-arrays (get x true)) (get x false))))
       (group-by :object-identifier)
       vals
       (map (partial apply merge))))


;; ================================================================
;; ==================  And now read property multiple =============
;; ================================================================

(defn find-max-refs [local-device-id device-id]
  (let [remote-device (some-> (events/cached-remote-devices local-device-id)
                              (get device-id))]
    (.getMaxReadMultipleReferences remote-device)))


(defn partition-object-property-references
  [local-device-id device-id obj-prop-references]
  (let [max-refs (find-max-refs local-device-id device-id)]
    (partition-all max-refs obj-prop-references)))

(defn read-property-multiple-request
  "Create a read-property-multiple request.
   [[object-identifier property-references]
    [object-identifier property-references]]"
  [obj-prop-references]
  (ReadPropertyMultipleRequest.
   (c/clojure->bacnet :sequence-of
                      (mapv (partial c/clojure->bacnet :read-access-specification)
                            obj-prop-references))))


(defn read-property-multiple*
  "read-access-specification should be of the form:
   [[object-identifier property-references]
    [object-identifier property-references]]"
  [local-device-id device-id obj-prop-references]
  ;; we partition the object-property-references to be compatible with
  ;; the remote device MaxReadMultipleReferences
  (let [results (for [refs (partition-object-property-references
                            local-device-id device-id obj-prop-references)]
                  (->> (read-property-multiple-request refs)
                       (services/send-request-promise local-device-id device-id)))]
    ;; the remote device might send an error for the entire
    ;; read-property-multiple request, even if only ONE object is
    ;; problematic (example error: :unkown-object). This means that we
    ;; can't know what is the cause of this error just by the request
    ;; response.

    ;; If ANY of the partitioned requests return an error or a failure,
    ;; just drop the entire thing. We can use higher order function to
    ;; send request individually and pinpoint the cause.
    (or (some #(when (not (:success %)) %) results) ;; return the first non-successful

        ;; if we only have successful requests, merge them and wrap
        ;; them back into a map with the :success key.
        (->> results
             (map :success)
             (apply concat)
             (group-by BACnet-array?)
             ((fn [x] (concat (assemble-arrays (get x true)) (get x false))))
             (group-by :object-identifier)
             vals
             (map (partial apply merge))
             (hash-map :success)))))


(defn split-opr [obj-prop-references]
  (let [qty (count obj-prop-references)]
    (if (> 4 qty)
      (partition-all (Math/floor (Math/sqrt qty)) obj-prop-references)
      (split-at (/ qty 2) obj-prop-references))))

(declare read-property-multiple)


(defn assemble-results
  "For each object-identifier, check if a property is present is
  present more than once. If it is, we are most probably dealing with
  a segmented array... just concat them together."
  [results]
  (->> results
       (apply concat)
       (remove nil?)
       (group-by :object-identifier)
       (map (fn [[oid results]]
              (assoc
               (apply (partial merge-with concat)
                      (map #(dissoc % :object-identifier) results))
               :object-identifier oid)))))


(defn read-array-in-chunks
  "Read the partitioned arrays in chunks and then assemble them back
  together." [local-device-id device-id partitioned-array]
  (let [read-result (for [opr (split-opr partitioned-array)]
                      (read-property-multiple local-device-id device-id opr))]
    (assemble-results read-result)))



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

(defn compact-obj-prop-ref
  "Inverse of 'expand-obj-prop-ref'."
  [obj-prop-refs]
  (for [[oid oid-props] (group-by first obj-prop-refs)]
    (concat [oid] (map last oid-props))))

(defn replace-special-identifier
  "For devices that don't support the special identifiers
   (i.e. :all, :required and :optional), return a list of properties.

   The :all won't be as the one defined by the BACnet standard,
   because we can't know for sure what are the properties. (Especially
   in the case of proprietary objects.)" [object-property-references]
  (for [single-object-property-references object-property-references]
    (let [[object-identifier & properties] single-object-property-references
          f (fn [x] (if (#{:all :required :optional} x)
                      (co/properties-by-option (first object-identifier) x) [x]))]
      (cons object-identifier
            (distinct (for [prop properties new-prop (f prop)] new-prop))))))

(defn when-coll
  "Apply function 'f' to coll only if it really is a collection.
   Return nil otherwise."
  [coll f]
  (when (coll? coll)
    (f coll)))


;; NOTE: Does not differentiate with a single device-id followed by an array.
;; [[[:device 123] [:object-list 1] [:object-list 2] ...]]
;; Might be wise to correct that.
(defn is-expanded-array?
  "Return true if the object-property-references is an expanded array
   Example :
   [[[:device 123] [:object-list 1]]
    [[:device 123] [:object-list 2]]]"
  [opr]
  (let [properties (map #(some-> %
                                 (when-coll last)
                                 (when-coll first)) opr)
        obj-id (map #(some-> % (when-coll first)) opr)]
    (and (apply = properties) ;; same property
         (apply = obj-id) ;; same object
         (every? identity properties))))

(defn read-property-multiple
  "read-access-specification should be of the form:
   [[object-identifier property-references]
    [object-identifier property-references]]

  In case of an error (example :unknown-object), will fallback to
  reading everything individually in order to be able to know which
  property-reference is problematic."
  [local-device-id device-id obj-prop-references]
  (let [expanded-array? (is-expanded-array? obj-prop-references)
        ;; some device propose segment windows that are far too small
        ;; for the expected payload, causing an error in BACnet4J.
        ;; "BACnetException Segment did not fit in segment window"
        ;; Until this is fixed, we try to manually split our requests
        ;; at 50
        read-result (if (and (> (count obj-prop-references) 50)
                             (not expanded-array?))
                      {:split-opr true} ;; above 50 OPR communication is starting to suffer.
                      (read-property-multiple* local-device-id device-id obj-prop-references))]
    (if-let [result (:success read-result)]
      result
      (do
        (cond
          ;; if we get an error related to object or property read
          ;; indiviually to pinpoint which object is problematic.
          (or (when-let [err (some-> read-result :error :error-class)]
                (some #{err} [:object :property])))

          (->> obj-prop-references
               replace-special-identifier
               expand-obj-prop-ref
               (read-individually local-device-id device-id))


          ;; size related for a single object.
          (and
           (size-related? (or (:abort read-result) (:reject read-result)))
           (= (count obj-prop-references) 1) ;; single property
           (not expanded-array?)) ;; not an array index

          (do (println "Error for : " (first obj-prop-references)
                       (size-related? (or (:abort read-result) (:reject read-result))))
              (println "Trying to read as an array (in chunks).")
              (state/set-request-response! read-result)
              (let [expanded-array (apply (partial expand-array local-device-id device-id)
                                          (first obj-prop-references))]
                (read-array-in-chunks local-device-id device-id expanded-array)))


          ;; size related multiple objects or multiple properties
          (or (:split-opr read-result)
              (= :unrecognized-service (:reject-reason (:reject read-result)))
              (and (size-related? (or (:abort read-result) (:reject read-result)))
                   (or (> (count obj-prop-references) 1) ;; too many objects
                       (> (count (first obj-prop-references)) 2)))) ;; too many properties

          ;; try to split into smaller read requests
          ;; (still multiple properties per request)
          (let [expand? (and (not (:split-opr read-result))
                             (= (count obj-prop-references) 1)
                             (> (count (first obj-prop-references)) 2))]
            ;; we should expand if we have a single object but too
            ;; many properties.
            (->> (for [opr (split-opr (if expand?
                                        (expand-obj-prop-ref obj-prop-references)
                                        obj-prop-references))]
                   (read-property-multiple local-device-id device-id opr))
                 assemble-results))


          (:timeout read-result)
          (throw (or (some-> read-result :timeout :timeout-error)
                     (Exception. "Timeout")))


          :else (do (println "Read-property-multiple error.")
                    (println obj-prop-references)
                    read-result))))))


;; ================================================================
;; ===========  Abstract the differences between the two ==========
;; ================================================================





(defn read-properties
  "Retrieve the property values form a remote device.
   Format for object-property-references should be:

   [ [[:analog-input 0] :description :object-name]   <--- multiple properties
     [[:device 1234] [:object-list 0]]                <--- array access
     [[:analog-ouput 1] :present-value]  ...]

  The result will be a collection of objects properties map.

  Example:

  ({:object-identifier [analog-input 1], :present-value 12.23}
   {:object-identifier [analog-input 2], :present-value 24.53, object-name \"Analog Input 2\"})"
  ([device-id object-property-references]
   (read-properties nil device-id object-property-references))
  ([local-device-id device-id object-property-references]
   object-property-references
   (if (-> (events/cached-remote-devices local-device-id)
           (get device-id)
           (.getServicesSupported)
           c/bacnet->clojure
           :read-property-multiple)
     (read-property-multiple local-device-id device-id object-property-references)
     (->> object-property-references
          replace-special-identifier
          expand-obj-prop-ref
          (read-individually local-device-id device-id)))))

(defn read-properties-multiple-objects
  "A convenience function to retrieve properties for multiple
  objects."
  ([device-id object-identifiers properties]
   (read-properties-multiple-objects nil device-id object-identifiers properties))
  ([local-device-id device-id object-identifiers properties]
   (->> (for [oid object-identifiers] (cons oid properties))
        vector
        (apply (partial read-properties local-device-id device-id)))))


;; ================================================================
;; =====================  Read range requests  ====================
;; ================================================================

;; ;; I wonder if there's a way to do this only using the read-properties functions...
;; ;; For now we rely on the underlying bacnet4j library.

(import (com.serotonin.bacnet4j.service.confirmed
         ReadRangeRequest
         ReadRangeRequest$ByPosition
         ReadRangeRequest$BySequenceNumber
         ReadRangeRequest$ByTime))


(defn read-range-request-by [reference range & by-what]
  (condp = (first by-what)
    :sequence (ReadRangeRequest$BySequenceNumber. (c/clojure->bacnet :unsigned-integer reference)
                                                  (c/clojure->bacnet :signed-integer range))
    :time (ReadRangeRequest$ByTime. (c/clojure->bacnet :date-time reference)
                                    (c/clojure->bacnet :signed-integer range))
    (ReadRangeRequest$ByPosition. (c/clojure->bacnet :unsigned-integer reference)
                                  (c/clojure->bacnet :signed-integer range)))) ;;default to :position


(defn read-range-request
  "'by-what?' can be :sequence, :time, or position (the default if
  none is provided)."
  [object-identifier property-identifier array-index [reference range] & by-what?]
  (ReadRangeRequest.
   (c/clojure->bacnet :object-identifier object-identifier)
   (c/clojure->bacnet :property-identifier property-identifier)
   (c/clojure->bacnet :unsigned-integer array-index)
   (read-range-request-by reference range (first by-what?))))

(defn read-range
  "'by-what?' can be :sequence, :time, or position (the default if
  none is provided)."
  [local-device-id device-id object-identifier property-identifier array-index [reference range] & by-what?]
  (->> (read-range-request object-identifier property-identifier array-index [reference range] by-what?)
       (services/send-request-promise local-device-id device-id)))
