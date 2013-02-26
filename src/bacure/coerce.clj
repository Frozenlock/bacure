(ns bacure.coerce
  (:require [clojure.string :as str]
            [clj-time.core :as time]
            [clj-time.coerce]
            [clj-time.local])
  (:import (com.serotonin.bacnet4j 
            service.acknowledgement.AcknowledgementService 
            service.acknowledgement.CreateObjectAck
            service.acknowledgement.ReadPropertyAck
            service.acknowledgement.ReadRangeAck
            service.confirmed.ConfirmedRequestService
            service.confirmed.CreateObjectRequest
            service.confirmed.DeleteObjectRequest
            service.confirmed.ReadPropertyConditionalRequest
            service.confirmed.ReadPropertyMultipleRequest
            service.confirmed.ReadPropertyRequest
            service.confirmed.WritePropertyMultipleRequest
            service.confirmed.WritePropertyRequest
            service.confirmed.ReinitializeDeviceRequest
            service.confirmed.AtomicReadFileRequest
            service.confirmed.ReadRangeRequest
            service.unconfirmed.WhoIsRequest
            type.AmbiguousValue
            type.constructed.Address
            type.constructed.BACnetError
            type.constructed.Destination
            type.constructed.DeviceObjectPropertyReference
            type.constructed.EventTransitionBits            
            type.constructed.PriorityArray
            type.constructed.PriorityValue
            type.constructed.PropertyReference
            type.constructed.PropertyValue            
            type.constructed.LimitEnable
            type.constructed.ReadAccessSpecification
            type.constructed.Recipient
            type.constructed.SequenceOf
            type.constructed.WriteAccessSpecification
            type.constructed.DateTime
            type.constructed.ObjectTypesSupported
            type.constructed.TimeStamp
            type.constructed.StatusFlags
            type.constructed.ShedLevel
            type.enumerated.EngineeringUnits            
            type.enumerated.EventState
            type.enumerated.NotifyType
            type.enumerated.ObjectType
            type.enumerated.PropertyIdentifier
            type.enumerated.Segmentation
            type.enumerated.Reliability
            type.primitive.CharacterString
            type.primitive.ObjectIdentifier
            type.primitive.Real
            type.primitive.UnsignedInteger
            type.primitive.SignedInteger
            type.primitive.Date
            type.primitive.Time
            util.PropertyReferences
            util.PropertyValues
            obj.BACnetObject)
           (java.util ArrayList List)))


(def ^:dynamic *drop-ambiguous*
  "Ambiguous values will be dropped unless this is bound to false." true)

;; functions to convert between camel-case and a more clojure-like style

(defn from-camel [string]
  (let [pre-string (subs string 0 1) ;;we want to keep the first uppercase letter, so we can reverse the process
        post-string (subs string 1)]
    (str pre-string (-> (str/replace post-string #"[A-Z][^A-Z]+" #(str \- (str/lower-case %)))
                        (str/replace #"([A-Z]+)" "-$1")))))

(defn to-camel [string] (str/replace string #"-." #(str/upper-case (last %))))

(defn string-name-to-keyword
  [object-type]
  (let [string (-> (str/replace (.toString object-type)  " " "-")
                   (str/lower-case)
                   (str/replace #"\(|\)|:" ""))]
    (when (seq string)
      (keyword string))))

(defn construct [klass & args]
    (clojure.lang.Reflector/invokeConstructor klass (into-array Object args)))


(defn subclass-to-map
  "Make a map of the subclasses and associate them with their integer
  value."[class]
  (let [qty (dec (count (.getDeclaredFields class)))]
    (into {}
          (for [i (range qty)
                :let [object (construct class i)]]
            (when-not (= name "serialVersionUID")
              [(string-name-to-keyword object) i])))))


(def prop-int-map
  (subclass-to-map PropertyIdentifier))

(defn make-property-identifier [prop-keyword]
  (PropertyIdentifier. (get prop-int-map prop-keyword)))

(def obj-int-map
  (subclass-to-map ObjectType))

(def engineering-units-map
  (subclass-to-map EngineeringUnits))


(def segmentation-int-map
  (subclass-to-map com.serotonin.bacnet4j.type.enumerated.Segmentation))

(def reliability-map
  "The .toString method doesn't return the name, but instead the
  string version of an integer. Thus we have to hardcode the names."
  {:communication-failure 12
   :configuration-error 10
   :multi-state-fault 9
   :no-fault-detected 0
   :no-output 6
   :no-sensor 1
   :open-loop 4
   :over-range 2
   :process-error 8
   :shorted-loop 5
   :under-range 3
   :unreliable-other 7})


(defn bean-map
  "Create a clojure map using `bean' and remove unwanted info"
  [java-object]
  (let [m (dissoc (bean java-object) :class :value)]
    (into {}
          (for [item m]
            [(keyword (from-camel (name (key item))))
             (val item)]))))
  


;;================================================================
;; Coerce to various datatypes functions
;;================================================================

(defn map-or-num [map key-or-num]
  (if (keyword? key-or-num)
    (get map key-or-num)
    key-or-num))

(defn c-object-type
  "Type can be either the integer or the keyword"[object-type]
  (ObjectType.
   (if (keyword? object-type)
     (if-let [[_ n] (re-find #"vendor-specific-(\d+)" (name object-type))]
       (read-string n)
       (get obj-int-map object-type))
     object-type)))

(defn c-object-identifier
  "Make an object identifier."
  [object-identifier]
    (ObjectIdentifier.
     (c-object-type (first object-identifier))
     (last object-identifier)))

(defn c-real [value]
  (Real. (float value)))

(defn c-character-string [value]
  (CharacterString. value))

(defn c-status-flag [{:keys[in-alarm fault overridden out-of-service]
                      :or {in-alarm false fault false overridden false out-of-service false}}]
  (StatusFlags. in-alarm fault overridden out-of-service))

(defn c-event-state [value]
  (let [subclass-map (subclass-to-map EventState)]
    (EventState. (get subclass-map value 0))))

(defn c-reliability [value]
  (let [subclass-map (subclass-to-map Reliability)]
    (Reliability. (or (map-or-num subclass-map value) 7))))

(defn c-boolean [bool]
  (com.serotonin.bacnet4j.type.primitive.Boolean. bool))

(defn c-unsigned [value]
  (UnsignedInteger. value))

(defn c-signed [value]
  (SignedInteger. value))

(defn c-engineering-units [value]
  (EngineeringUnits. (map-or-num  engineering-units-map value)))

(defn c-limit-enable [{:keys [low-limit-enable high-limit-enable]
                       :or {low-limit-enable false high-limit-enable false}}]
  (LimitEnable. low-limit-enable high-limit-enable))

(defn c-event-transition-bits [{:keys [to-off-normal to-fault to-normal]
                                :or {to-off-normal false to-fault false to-normal false}}]
  (EventTransitionBits. to-off-normal to-fault to-normal))

(defn c-notify-type [value]
  (NotifyType. value))

(defn c-date [string] ;;probably unnecessary because we have date-time
  (let [t (clj-time.local/to-local-date-time
           (clj-time.format/parse
            (clj-time.format/formatters :year-month-day) string))]
    (Date. (.toGregorianCalendar t))))

(defn c-date-time [string]
  (let [t (clj-time.local/to-local-date-time
           (clj-time.format/parse
            (clj-time.format/formatters :date-time) string))]
    (DateTime. (Date. (.toGregorianCalendar t))
               (Time.)))) ;we don't need to add any time

(defn c-time [string]
  (let [t (clj-time.local/to-local-date-time
           (clj-time.format/parse
            (clj-time.format/formatters :hour-minute-second-ms) string))]
    (com.serotonin.bacnet4j.type.primitive.Time.
     (time/hour t)
     (time/minute t)
     (time/sec t)
     (time/milli t))))
  
(defn c-time-stamp [string]
  (TimeStamp.
   (c-date-time string)))


(defn coerce-supported [java-object smap]
  (doseq [item smap]
    (clojure.lang.Reflector/invokeInstanceMethod
     java-object
     (str "set" (to-camel (str/capitalize (name (key item)))))
     (to-array [(val item)])))
  java-object)

(defn c-services-supported [smap]
  (let [services-supported (com.serotonin.bacnet4j.type.constructed.ServicesSupported.)]
    (coerce-supported services-supported smap)))

(defn c-object-types-supported [smap]
  (let [object-types-supported (com.serotonin.bacnet4j.type.constructed.ObjectTypesSupported.)]
    (coerce-supported object-types-supported smap)))

(defn c-segmentation [value]
  (Segmentation. (map-or-num segmentation-int-map value)))

(defn c-property-value [property-identifier property-value]
  (PropertyValue. property-identifier property-value))

(defn c-array
  "Convert the collection to an array of values using c-fn."
  [c-fn coll]
  (SequenceOf. (ArrayList. (map c-fn coll))))
  


;;================================================================
;; Convert to Clojure objects
;;================================================================  

;; Might be wise to use multimethods here, instead of a protocol.

(defprotocol BacnetClojure
  (bacnet->clojure [o]))


(extend-protocol BacnetClojure

  nil
  (bacnet->clojure [o]
    nil)
  
  com.serotonin.bacnet4j.type.constructed.StatusFlags
  (bacnet->clojure [^StatusFlags o]
    {:in-alarm (.isInAlarm o)
     :fault (.isFault o)
     :out-of-service (.isOutOfService o)
     :overridden (.isOverridden o)})

  com.serotonin.bacnet4j.type.primitive.CharacterString
  (bacnet->clojure [^CharacterString o]
    (.toString o))

  com.serotonin.bacnet4j.type.enumerated.ObjectType
  (bacnet->clojure [^ObjectType o]
    (string-name-to-keyword o))

  com.serotonin.bacnet4j.type.enumerated.Segmentation
  (bacnet->clojure [^Segmentation o]
    (string-name-to-keyword o))
  
  com.serotonin.bacnet4j.type.primitive.Real
  (bacnet->clojure [^Real o]
    (.floatValue o))

  com.serotonin.bacnet4j.type.primitive.ObjectIdentifier
  (bacnet->clojure [^ObjectIdentifier o]
    [(keyword (bacnet->clojure (.getObjectType o)))
     (.getInstanceNumber o)])

  com.serotonin.bacnet4j.type.enumerated.EventState
  (bacnet->clojure [^EventState o]
    (string-name-to-keyword o))

  com.serotonin.bacnet4j.type.enumerated.Reliability
  (bacnet->clojure [^Reliability o]
    (first (->> reliability-map
                (filter (comp #{(.intValue o)} val))
                (map key))))

  com.serotonin.bacnet4j.type.primitive.Boolean
  (bacnet->clojure [^com.serotonin.bacnet4j.type.primitive.Boolean o]
    (.booleanValue o))

  com.serotonin.bacnet4j.type.primitive.UnsignedInteger
  (bacnet->clojure [^com.serotonin.bacnet4j.type.primitive.UnsignedInteger o]
    (.intValue o))

  com.serotonin.bacnet4j.type.primitive.SignedInteger
  (bacnet->clojure [^com.serotonin.bacnet4j.type.primitive.SignedInteger o]
    (.intValue o))
  
  com.serotonin.bacnet4j.type.enumerated.EngineeringUnits
  (bacnet->clojure [^EngineeringUnits o]
    (string-name-to-keyword o))

  com.serotonin.bacnet4j.type.constructed.LimitEnable
  (bacnet->clojure [^LimitEnable o]
    {:low-limit-enable (.isLowLimitEnable o)
     :high-limit-enable (.isHighLimitEnable o)})

  com.serotonin.bacnet4j.type.constructed.EventTransitionBits
  (bacnet->clojure [^EventTransitionBits o]
    {:to-normal (.isToNormal o)
     :to-fault (.isToFault o)
     :to-off-normal (.isToOffnormal o)})

  com.serotonin.bacnet4j.type.enumerated.NotifyType
  (bacnet->clojure [^NotifyType o]
    (.intValue o))

  com.serotonin.bacnet4j.type.constructed.DateTime
  (bacnet->clojure [^DateTime o]
    (clj-time.format/unparse
     (clj-time.format/formatters :date-time)
     (clj-time.coerce/from-long (.getTimeMillis o))))

  com.serotonin.bacnet4j.type.constructed.DeviceObjectPropertyReference
  (bacnet->clojure [^DeviceObjectPropertyReference o]
    {:device-identifier (bacnet->clojure (.getDeviceIdentifier o))
     :object-identifier (bacnet->clojure (.getObjectIdentifier o))
     :property-array-index (bacnet->clojure (.getPropertyArrayIndex o))
     :property-identifier (bacnet->clojure (.getPropertyIdentifier o))})
    
  com.serotonin.bacnet4j.type.primitive.Date ;; bastard format...
  (bacnet->clojure [^Date o]
    (clj-time.format/unparse
     (clj-time.format/formatters :year-month-day)
     (clj-time.core/date-time (+ 1900 (.getYear o)) ;; grrr...
                              (.getId (.getMonth o)) ;; WHY?! Why a class for the month?
                              (.getDay o))))
  
  com.serotonin.bacnet4j.type.constructed.TimeStamp
  (bacnet->clojure [^TimeStamp o]
    (cond (.isDateTime o) (bacnet->clojure (.getDateTime o))
          :else (throw (Exception. "The time-stamp isn't in a BACnet date-time format."))))

  com.serotonin.bacnet4j.type.primitive.Time
  (bacnet->clojure [^com.serotonin.bacnet4j.type.primitive.Time o]
    (.toString o)) ;; oh god, why so many time format... just take ISO8601 already
  
  com.serotonin.bacnet4j.type.constructed.SequenceOf
  (bacnet->clojure [^SequenceOf o]
    (into [] (map bacnet->clojure o)))

  com.serotonin.bacnet4j.type.constructed.ServicesSupported
  (bacnet->clojure [^ServicesSupported o]
    (bean-map o))

  com.serotonin.bacnet4j.type.constructed.PriorityValue
  (bacnet->clojure [^PriorityValue o]
    (.getIntegerValue o))
  
  com.serotonin.bacnet4j.type.constructed.PropertyValue
  (bacnet->clojure [^PropertyValue o]
    (into [] (map bacnet->clojure [(.getPropertyIdentifier o)(.getValue o)])))

  com.serotonin.bacnet4j.type.constructed.ShedLevel
  (bacnet->clojure [^ShedLevel o]
    (bacnet->clojure (.getPercent o)))
  
  
  com.serotonin.bacnet4j.util.PropertyValues
  (bacnet->clojure [^PropertyValues o]
    (letfn [(get-values [p-ref o]
              (let [object-identifier (.getObjectIdentifier p-ref)
                    value (bacnet->clojure (.getNullOnError o object-identifier (.getPropertyIdentifier p-ref)))]
                (into {}
                      [[:object-identifier (bacnet->clojure object-identifier)]
                       [(string-name-to-keyword (.getPropertyIdentifier p-ref)) value]])))]
      ;; get-values return values in the form of {:object-identifier ... :prop-name value}
      (->> (for [p-ref o]
             (get-values p-ref o))
           (group-by :object-identifier)
           vals
           (map #(apply merge %)))))

  com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier
  (bacnet->clojure [^PropertyIdentifier o]
    (string-name-to-keyword o))

  com.serotonin.bacnet4j.obj.BACnetObject
  (bacnet->clojure [^BACnetObject o]
    (let [properties-id (seq (:properties (bean o)))]
      ;; doesn't seem to be a java method to get the properties list.
      ;; Hopefully `bean' won't be much of a drag
      (into {}
            (for [p-id properties-id]
              (when-let [prop-value (bacnet->clojure (.getProperty o p-id))]
                [(bacnet->clojure p-id) prop-value])))))

  com.serotonin.bacnet4j.type.constructed.ObjectTypesSupported
  (bacnet->clojure [^ObjectTypesSupported o]
    (bean-map o))

  com.serotonin.bacnet4j.type.constructed.BACnetError
  (bacnet->clojure [^BACnetError o]
    (throw (Exception. (.toString o))));;throw an error
  
  com.serotonin.bacnet4j.type.AmbiguousValue
  (bacnet->clojure [^AmbiguousValue o]
    (if-not *drop-ambiguous* (.hashCode o)
            nil)) ;; drop it! Ambiguous value was causing a lot of issues with
                  ;; higher order functions, especially in case of comparison.

  
  clojure.lang.PersistentHashMap
  (bacnet->clojure [^clojure.lang.PersistentHashMap o]
    (into {} (for [[k v] o] [k (bacnet->clojure v)]))))


;;================================================================


;; here we associate, for every object, what datatype-fn should be
;; used to encode the data, with the property.

;; *** Some interesting properties found in com.serotonin.bacnet4j.obj.ObjectProperties
;; Perhaps I won't have to make my own datatype/object-properties table!


(defn encode-properties-values
  "Given an object-map, return a map of the encoded properties. Will
  ignore any property that is not defined for a given object type."
  [obj-map encode-fns]
  (into {}
        (for [current-property encode-fns]
          (let [property-name (key current-property)
                encoding-fn (val current-property)]
            (when-let [value (get obj-map property-name)]
              (when-let [encoded-value (encoding-fn value)]
                [property-name encoded-value]))))))


(def test-object
  {:object-identifier [:analog-input 1]
   :present-value 12
   :description "Test analog input"
   :device-type "Test device"
   :units :degrees-celsius
   :reliability :no-fault-detected
   :object-type :analog-input ;; yup, redundant information with object-identifier... 
   :status-flags {:in-alarm true, :fault false, :out-of-service true, :overridden false}
   :event-time-stamps ["2012-11-25T23:41:42.499Z" "2012-11-25T23:41:42.499Z" "2012-11-25T23:41:42.499Z"]})

(def test-object-2
  {:notification-class 4194303,
 :event-enable
 {:to-normal false, :to-fault false, :to-off-normal false},
 :event-time-stamps
 ["2155-07-23T23:19:17.550Z"
  "2155-07-23T23:19:17.550Z"
  "2155-07-23T23:19:17.550Z"],
 :present-value 0.0,
 :unknown-9997 "[44,42,b5,5,1f]",
 :unknown-9999 "[32,ff,38]",
 :object-type :analog-input,
 :unknown-9998 "[21,5a]",
 :object-name "ANALOG INPUT 1",
 :acked-transitions
 {:to-normal true, :to-fault true, :to-off-normal true},
 :notify-type 0,
 :status-flags
 {:in-alarm false,
  :fault false,
  :out-of-service false,
  :overridden false},
 :time-delay 0,
 :low-limit 0.0,
 :units :percent,
 :limit-enable {:low-limit-enable false, :high-limit-enable false},
 :high-limit 0.0,
 :reliability :communication-failure,
 :event-state :normal,
 :out-of-service false,
 :object-identifier [:analog-input 1],
 :description "ANALOG INPUT 1",
 :deadband 0.0})


(defmulti encode :object-type)

(defmethod encode :default [x]
  (throw (Exception. (str "No method implemented to convert into this object."))))

(defmethod encode :analog-input [obj-map]
  (let [encode-fns {:object-identifier c-object-identifier
                    :object-name c-character-string
                    :object-type c-object-type
                    :present-value c-real
                    :description c-character-string
                    :device-type c-character-string
                    :status-flags c-status-flag
                    :event-state c-event-state
                    :reliability c-reliability
                    :out-of-service c-boolean
                    :update-interval c-unsigned
                    :units c-engineering-units
                    :min-pres-value c-real
                    :max-pres-value c-real
                    :resolution c-real
                    :cov-increment c-real
                    :time-delay c-unsigned
                    :notification-class c-unsigned
                    :high-limit c-real
                    :low-limit c-real
                    :deadband c-real
                    :limit-enable c-limit-enable
                    :event-enable c-event-transition-bits
                    :acked-transitions c-event-transition-bits
                    :notify-type c-notify-type
                    :event-time-stamps #(c-array c-time-stamp %)
                    :profile-name c-character-string}]
    (encode-properties-values obj-map encode-fns)))

(defn encode-properties
  "Encode an object map into a sequence of bacnet4j property-values.
  Remove-keys can be used to remove read-only properties before
  sending a command." [obj-map & remove-keys]
  (let [encoded-values-map (-> (dissoc obj-map remove-keys) encode)]
    (c-array #(c-property-value (make-property-identifier (key %)) (val %)) encoded-values-map)))