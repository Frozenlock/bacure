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
            type.constructed.AccumulatorRecord
            type.constructed.AccumulatorRecord$AccumulatorStatus
            type.constructed.ActionCommand
            type.constructed.ActionList
            type.constructed.Address
            type.constructed.BACnetError
            type.constructed.CovSubscription
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
            type.constructed.ServicesSupported
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
            obj.BACnetObject
            obj.PropertyTypeDefinition
            obj.ObjectProperties)
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
  {:both 0, :transmit 1, :receive 2, :none 3, :unknown 4})

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

(def accumulator-status-map
  "The .toString method doesn't return the name, but instead the
  string version of an integer. Thus we have to hardcode the names."
  {:normal 0
   :starting 1
   :recovered 2
   :abnormal 3
   :failed 4})

(def services-list
  "List of services associated with their bit-string value. We need to
   keep the ordering here, so no map."
  [[:acknowledge-alarm 0]
   [:confirmed-cov-notification 1]
   [:confirmed-event-notification 2]
   [:get-alarm-summary 3]
   [:get-enrollment-summary 4]
   [:subscribe-cov 5]
   [:atomic-read-file 6]
   [:atomic-write-file 7]
   [:add-list-element 8]
   [:remove-list-element 9]
   [:create-object 10]
   [:delete-object 11]
   [:read-property 12]
   [:read-property-conditional 13]
   [:read-property-multiple 14]
   [:write-property 15]
   [:write-property-multiple 16]
   [:device-communication-control 17]
   [:confirmed-private-transfer 18]
   [:confirmed-text-message 19]
   [:reinitialize-device 20]
   [:vt-open 21]
   [:vt-close 22]
   [:vt-data 23]
   [:authenticate 24]
   [:request-key 25]
   [:i-am 26]
   [:i-have 27]
   [:unconfirmed-cov-notification 28]
   [:unconfirmed-event-notification 29]
   [:unconfirmed-private-transfer 30]
   [:unconfirmed-text-message 31]
   [:time-synchronization 32]
   [:who-has 33]
   [:who-is 34]
   ;;services added after 1995
   [:read-range 35]
   [:utc-time-synchronization 36]
   [:life-safety-operation 37]
   [:subscribe-cov-property 38]
   [:get-event-information 39]])

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

(defn c-property-identifier [prop-keyword]
  (PropertyIdentifier. (get prop-int-map prop-keyword)))

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
  (let [subclass-map reliability-map]
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

(defn c-accumulator-status [value]
  (let [subclass-map accumulator-status-map]
    (AccumulatorRecord$AccumulatorStatus. (or (map-or-num subclass-map value) 0))))

(defn c-accumulator [m]
  (let [{:keys [timestamp present-value accumulated-value accumulator-status]} m]
    (AccumulatorRecord. (c-date-time timestamp)
                        (c-unsigned present-value)
                        (c-unsigned accumulated-value)
                        (c-accumulator-status accumulator-status))))


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
  (Segmentation. (or (map-or-num segmentation-int-map value) :unknown)))

(defn c-property-value [property-identifier property-value]
  (PropertyValue. property-identifier property-value))



;; DOES NOT WORK! Need to come back to this one
(defn c-action-command [m]
  (let [{:keys [device-identifier object-identifier property-identifier
                property-array-index property-value priority
                post-delay quit-on-failure write-successful]} m]
    (ActionCommand. (c-object-identifier device-identifier)
                    (c-object-identifier object-identifier)
                    (c-property-identifier property-identifier)
                    (c-unsigned property-array-index)
                    (c-property-value (c-property-identifier property-identifier)
                                      property-value)
                    (c-unsigned priority)
                    (c-unsigned post-delay)
                    (c-boolean quit-on-failure)
                    (c-boolean write-successful))))

(defn c-array
  "Convert the collection to an array of values using c-fn."
  [c-fn coll]
  (SequenceOf. (ArrayList. (map c-fn coll))))

(defn c-action-list [coll]
  (c-array c-action-command coll))


;;================================================================
;; Convert to Clojure objects
;;================================================================  

;; Might be wise to use multimethods here, instead of a protocol.

(defmulti bacnet->clojure class)

(defmethod bacnet->clojure :default [x]
  (str "No method YET for " (.toString x)))

;; methods for type 'constructed'

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.AccumulatorRecord
  [^AccumulatorRecord o]
  {:timestamp (bacnet->clojure (.getTimestamp o))
   :present-value (bacnet->clojure (.getPresentValue o))
   :accumulated-value (bacnet->clojure (.getAccumulatedValue o))
   :accumulator-status (bacnet->clojure (.getAccumulatorStatus o))})

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.AccumulatorRecord$AccumulatorStatus
  [^AccumulatorRecord$AccumulatorStatus o]
  (first (->> accumulator-status-map
              (filter (comp #{(.intValue o)} val))
              (map key))))
;(bacnet->clojure (c-accumulator {:timestamp (.toString (clj-time.core/now)) :present-value 0 :accumulated-value 10 :accumulator-status :normal}))  

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ActionCommand
  [^ActionCommand o]
  {:device-identifier (bacnet->clojure (.getDeviceIdentifier o))
   :object-identifier (bacnet->clojure (.getObjectIdentifier o))
   :property-identifier (bacnet->clojure (.getPropertyIdentifier o))
   :property-array-index (bacnet->clojure (.getPropertyArrayIndex o))
   :property-value (bacnet->clojure (.getPropertyValue o))
   :priority (bacnet->clojure (.getPriority o))
   :post-delay (bacnet->clojure (.getPostDelay o))
   :quit-on-failure (bacnet->clojure (.getQuitOnFailure o))
   :write-successful (bacnet->clojure (.getWriteSuccessful o))})

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ActionList
  [^ActionList o]
  (bacnet->clojure (.getAction o)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.BACnetError
  [^BACnetError o]
  (throw (Exception. (.toString o))));;throw an error

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.DateTime
  [^DateTime o]
  (clj-time.format/unparse
     (clj-time.format/formatters :date-time)
     (clj-time.coerce/from-long (.getTimeMillis o))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.DeviceObjectPropertyReference
  [^DeviceObjectPropertyReference o]
  {:device-identifier (bacnet->clojure (.getDeviceIdentifier o))
   :object-identifier (bacnet->clojure (.getObjectIdentifier o))
   :property-array-index (bacnet->clojure (.getPropertyArrayIndex o))
   :property-identifier (bacnet->clojure (.getPropertyIdentifier o))})

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.EventTransitionBits
  [^EventTransitionBits o]
  {:to-normal (.isToNormal o)
     :to-fault (.isToFault o)
     :to-off-normal (.isToOffnormal o)})

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.LimitEnable
  [^LimitEnable o]
  {:low-limit-enable (.isLowLimitEnable o)
     :high-limit-enable (.isHighLimitEnable o)})

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ObjectTypesSupported
  [^ObjectTypesSupported o]
  (bean-map o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.PriorityValue
  [^PriorityValue o]
  (.getIntegerValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.PropertyValue
  [^PropertyValue o]
  (into [] (map bacnet->clojure [(.getPropertyIdentifier o)(.getValue o)])))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.TimeStamp
  [^TimeStamp o]
  (cond (.isDateTime o) (bacnet->clojure (.getDateTime o))
        :else (throw (Exception. "The time-stamp isn't in a BACnet date-time format."))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.SequenceOf
  [^SequenceOf o]
  (into [] (map bacnet->clojure o)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ServicesSupported
  [^ServicesSupported o]
  (->> (seq (.getValue o))
       (interleave (map first services-list))
       (apply hash-map)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ShedLevel
  [^ShedLevel o]
  (bacnet->clojure (.getPercent o)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.StatusFlags
  [^StatusFlags o]
    {:in-alarm (.isInAlarm o)
     :fault (.isFault o)
     :out-of-service (.isOutOfService o)
     :overridden (.isOverridden o)})


;; methods for type 'primitive'

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.CharacterString
  [^CharacterString o]
  (.toString o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Real
  [^Real o]
  (.floatValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.ObjectIdentifier
  [^ObjectIdentifier o]
  [(keyword (bacnet->clojure (.getObjectType o)))
   (.getInstanceNumber o)])

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Boolean
  [^com.serotonin.bacnet4j.type.primitive.Boolean o]
  (.booleanValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.UnsignedInteger
  [^com.serotonin.bacnet4j.type.primitive.UnsignedInteger o]
  (.intValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.SignedInteger
  [^com.serotonin.bacnet4j.type.primitive.SignedInteger o]
  (.intValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Date ;; bastard format...
  [^Date o]
  (clj-time.format/unparse
   (clj-time.format/formatters :year-month-day)
   (clj-time.core/date-time (+ 1900 (.getYear o)) ;; grrr...
                            (.getId (.getMonth o)) ;; WHY?! Why a class for the month?
                            (.getDay o))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Time
  [^com.serotonin.bacnet4j.type.primitive.Time o]
  (.toString o)) ;; oh god, why so many time format... just take ISO8601 already

;; methods for type 'enumerated'

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.ObjectType
  [^ObjectType o]
  (string-name-to-keyword o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.Segmentation
  [^Segmentation o]
  (string-name-to-keyword o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.EventState
  [^EventState o]
  (string-name-to-keyword o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.Reliability
  [^Reliability o]
  (first (->> reliability-map
              (filter (comp #{(.intValue o)} val))
              (map key))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.EngineeringUnits
  [^EngineeringUnits o]
  (string-name-to-keyword o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.NotifyType
  [^NotifyType o]
  (.intValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier
  [^PropertyIdentifier o]
  (string-name-to-keyword o))


;; methods for 'util'

(defmethod bacnet->clojure com.serotonin.bacnet4j.util.PropertyValues
  [^PropertyValues o]
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

;; methods for 'obj'

(defmethod bacnet->clojure com.serotonin.bacnet4j.obj.BACnetObject
  [^BACnetObject o]
  (let [properties-id (seq (:properties (bean o)))]
      ;; doesn't seem to be a java method to get the properties list.
      ;; Hopefully `bean' won't be much of a drag
      (into {}
            (for [p-id properties-id]
              (when-let [prop-value (bacnet->clojure (.getProperty o p-id))]
                [(bacnet->clojure p-id) prop-value])))))


;; methods for type 'AmbiguousValue'

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.AmbiguousValue
  [^AmbiguousValue o]
  (if-not *drop-ambiguous* (.hashCode o)
            nil)) ;; drop it! Ambiguous value was causing a lot of issues with
                  ;; higher order functions, especially in case of comparison.

;; methods for 'obj'

(defmethod bacnet->clojure com.serotonin.bacnet4j.obj.PropertyTypeDefinition
  [^PropertyTypeDefinition o]
  [(bacnet->clojure (.getPropertyIdentifier o))
   {:optional (.isOptional o)
    :required (.isRequired o)
    :sequence (.isSequence o)}])

;; methods for clojure stuff

(defmethod bacnet->clojure clojure.lang.PersistentHashMap
  [^clojure.lang.PersistentHashMap o]
  (into {} (for [[k v] o] [k (bacnet->clojure v)])))

(defmethod bacnet->clojure clojure.lang.PersistentArrayMap
  [^clojure.lang.PersistentArrayMap o]
  (into {} (for [[k v] o] [k (bacnet->clojure v)])))

(defmethod bacnet->clojure nil [_] nil)

;; methods for java

(defmethod bacnet->clojure java.util.ArrayList
  [^ArrayList o]
  (map bacnet->clojure (seq o)))

;;================================================================

(defn object-profile
  "Given an object type, return the properties it should have, and if
   they are :required, :optional, or :sequence." [object-type]
   (->> (ObjectProperties/getRequiredPropertyTypeDefinitions
         (c-object-type :analog-input))
        (map bacnet->clojure)))

(defn properties-by-option
  "Return a list or properties. `option' should
  be :required, :optional, :sequence or :all."
  [object-type option]
  (let [profile (object-profile object-type)]
    (->> (if (= option :all) profile
             (filter (comp option last) profile))
         (map first))))
  
   


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