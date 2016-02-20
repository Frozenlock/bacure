(ns bacure.coerce.obj
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.primitive :as p]
            [bacure.coerce.type.enumerated :as e])
  (:import [com.serotonin.bacnet4j.obj
            ObjectProperties
            BACnetObject
            PropertyTypeDefinition]))



;; we only go to clojure... no need to do a clojure->bacnet method

(defmethod bacnet->clojure PropertyTypeDefinition
  [^PropertyTypeDefinition o]
  [(bacnet->clojure (.getPropertyIdentifier o))
   {:object-type (bacnet->clojure (.getObjectType o))
    :type (-> (re-find #"[A-Za-z0-9]*$" (.toString (.getClazz o)))
              (c/from-camel)
              (clojure.string/lower-case)
              (keyword))
    :optional (bacnet->clojure (.isOptional o))
    :required (bacnet->clojure (.isRequired o))
    :sequence (bacnet->clojure (.isSequence o))}])

(defn property-type-definitions
  "Given an object type, return the properties it should have, and if
   they are :required, :optional, or :sequence." 
  [object-type]
   (->> (ObjectProperties/getPropertyTypeDefinitions
         (clojure->bacnet :object-type object-type))
        (map bacnet->clojure)
        (into {})))

(defn properties-by-option
  "Return a list or properties. `option' should
  be :required, :optional, :sequence or :all."
  [object-type option]
  (assert (some #{option} [:required :optional :sequence :all]))
  (let [profile (property-type-definitions object-type)]
    (if (= option :all) (keys profile)
        (for [[k v] profile :when (get v option)] k))))

(defn determine-property-value-type
  "Given an object-type and a property-identifier, return a map with
  the :type and if it's a :sequence."
  [object-type property-identifier]
  (get (property-type-definitions object-type)
       (if (keyword? property-identifier)
         property-identifier
         (c/int-to-keyword e/property-identifier-map property-identifier))))

(defn get-object-type 
  "Find the object type in an object-map (either from
  the :object-type, or in the :object-identifier)."
  [obj-map]
  (or (:object-type obj-map) 
      (first (:object-identifier obj-map))))

(defn force-type
  "Associate the value with a specific type. Use only before
  encoding.
  
  Ex: {:some-property (force-type \"some-value\" :character-string)}" 
  [value type-keyword]
  {::forced-type type-keyword
   :value value})

(defn encode-property-value
  "Encode the property value depending on what type it should be given
  the object.
  
  A type can be specified when required (proprietary properties) by
  using the function `force-type'."
  [object-type property-identifier value]
  (let [value-type (determine-property-value-type object-type property-identifier)
        forced-type (when (map? value)
                      (::forced-type value))
        type-keyword (or forced-type (:type value-type))
        encode-fn (partial clojure->bacnet type-keyword)
        naked-value (if forced-type (:value value) value)]
    (if-not type-keyword 
      (throw 
       (Exception. 
        (str "Couldn't find the type associated with property '"
             property-identifier 
             "'. You can try to use the function `bacure.coerce.obj/force-type'."))) )
    (if (:sequence value-type)
      (do (if-not (coll? naked-value)
            (throw (Exception. (str "The property '"property-identifier "' requires a collection."))))
          (clojure->bacnet :sequence-of (map encode-fn naked-value)))
      (encode-fn naked-value))))

(defn encode-properties-values
  "Take an object-map (a map of properties and values) and encode the
  values (not the properties) into their corresponding bacnet4j type. For
  example, a clojure number \"1\" might be encoded into a real, or
  into an unisgned integer, depending on the object."
  [obj-map]
  (let [o-t (get-object-type obj-map)]
    (into {}
          (for [[property value] obj-map]
            [property (encode-property-value o-t property value)]))))

(defn encode-properties
  "Encode an object map into a sequence of bacnet4j property-values.
  Remove-keys can be used to remove read-only properties before
  sending a command." [obj-map & remove-keys]
  (let [encoded-values-map (-> (encode-properties-values obj-map) 
                               (dissoc (first remove-keys)))
        encoded-properties-values (map #(clojure->bacnet 
                                         :property-value*
                                         [(clojure->bacnet :property-identifier (key %)) (val %)])
                                       encoded-values-map)]
    (clojure->bacnet :sequence-of encoded-properties-values)))


;;;


;; (defmethod bacnet->clojure BACnetObject
;;   [^BACnetObject o]
;;   (let [properties-id (seq (:properties (bean o)))]
;;       ;; doesn't seem to be a java method to get the properties list.
;;       ;; Hopefully `bean' won't be much of a drag
;;       (into {}
;;             (for [p-id properties-id]
;;               (when-let [prop-value (bacnet->clojure (.getProperty o p-id))]
;;                 [(bacnet->clojure p-id) prop-value])))))
