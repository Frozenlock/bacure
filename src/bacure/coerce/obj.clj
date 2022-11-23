(ns bacure.coerce.obj
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.primitive :as p]
            [bacure.coerce.type.enumerated :as e])
  (:import com.serotonin.bacnet4j.RemoteObject
           (com.serotonin.bacnet4j.obj ObjectProperties
                                       BACnetObject
                                       PropertyTypeDefinition
                                       ObjectPropertyTypeDefinition)))



;; we only go to clojure... no need to do a clojure->bacnet method
(defmethod bacnet->clojure RemoteObject
  [^RemoteObject o]

  {:object-identifier (-> (.getObjectIdentifier o)
                          bacnet->clojure)
   :object-name (-> (.getObjectName o)
                    bacnet->clojure)})

(defmethod bacnet->clojure PropertyTypeDefinition
  [^PropertyTypeDefinition o]
  {:type (-> (re-find #"[A-Za-z0-9]*$" (.toString (.getClazz o)))
             (c/from-camel)
             (clojure.string/lower-case)
             (keyword))
   :property-identifier (bacnet->clojure (.getPropertyIdentifier o))
   :array (.isArray o)
   :array-length (.getArrayLength o)
   :collection (.isCollection o)
   :list (.isList o)})


(defmethod bacnet->clojure ObjectPropertyTypeDefinition
  [^ObjectPropertyTypeDefinition o]
  (let [{:keys [property-identifier type array collection list array-length]}
        (bacnet->clojure (.getPropertyTypeDefinition o))]
    [property-identifier
     {:type type
      :optional (bacnet->clojure (.isOptional o))
      :required (bacnet->clojure (.isRequired o))
      :sequence (or array collection list)
      :array-length array-length}]))

(defn property-type-definitions
  "Given an object type, return the properties it should have, and if
   they are :required, :optional, or :sequence."
  [object-type]
  (->> (ObjectProperties/getObjectPropertyTypeDefinitions
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
        naked-value (if forced-type (:value value) value)
        length (:array-length value-type)
        pad (fn [coll]
              (if (> length 0)
                (take length (concat coll (repeat nil)))
                coll))]
    (if-not type-keyword
      (throw
       (Exception.
        (str "Couldn't find the type associated with property '"
             property-identifier
             "'. You can try to use the function `bacure.coerce.obj/force-type'."))))
    (if (:sequence value-type)
      (if (not (or (nil? naked-value) (coll? naked-value)))
        (throw (Exception. (str "The property '"property-identifier "' requires a collection.")))
        (clojure->bacnet :sequence-of (map encode-fn (pad naked-value))))
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
  (let [o-t (get-object-type obj-map)
        encoded-properties-values (for [[k v] (apply dissoc obj-map remove-keys)]
                                    (c/clojure->bacnet :property-value*
                                                       {:property-identifier k
                                                        :value v
                                                        :object-type o-t}))]
    (clojure->bacnet :sequence-of encoded-properties-values)))


;;;


(defmethod bacnet->clojure BACnetObject
  [^BACnetObject o]
  ;; unfortunately there doesn't appear to be a method retrieve all
  ;; the object properties at once. While it might be a little
  ;; wasteful, the most straightforward alternative appears to try to
  ;; get the value of all the possible properties for the given object
  ;; type.
  (let [[object-type object-instance] (c/bacnet->clojure (.getId o))
        possible-properties           (properties-by-option object-type :all)]
    (with-meta
      (->> (for [prop  possible-properties
                 :let  [value (.get o (c/clojure->bacnet :property-identifier prop))]
                 :when value]
             [prop (c/bacnet->clojure value)])
           (into {}))
      ;; add the local device as metadata to ease the round-trip from Clojure to BACnet
      {::local-device (.getLocalDevice o)})))

;; The local device 'addObject' method expects a BACnetObject.
;; However, the BACnetObject needs a local device to be initiated.
;; This means that we can't really have a pure 'data' bacnet-object.

(defn bacnet-object-with-local-device
  "Add a local device to the map metadata to allow conversion (and
  automatic object creation) to BACnet."
  [object-map local-device-object]
  (with-meta object-map {::local-device local-device-object}))

(defmethod clojure->bacnet :bacnet-object
  [_ value]
  (if-let [local-device (::local-device (meta value))]
    (let [bacnet-object (BACnetObject. local-device
                                       (c/clojure->bacnet :object-identifier (:object-identifier value)))]
      ;; now write the values
      (doseq [[property value] (encode-properties-values value)]
        (.writePropertyInternal bacnet-object (c/clojure->bacnet :property-identifier property)
              value))
      bacnet-object)
    (throw (Exception. (str "Missing local device in the object-map with identfier : " (:object-identifier value)
                            "\nMake sure to use the local device function 'add-object!'")))))
