(ns bacure.coerce.type.constructed
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.primitive :as p]
            [bacure.coerce.type.enumerated :as e]
            [bacure.coerce.obj :as obj]
            [clojure.string :as str]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.local :as tl])
  (:import [com.serotonin.bacnet4j.type.constructed
            AccessRule
            AccessRule$TimeRangeSpecifier
            AccessRule$LocationSpecifier
            AccumulatorRecord
            AccumulatorRecord$AccumulatorStatus
            ActionCommand
            ActionList
            Address
            Choice
            DailySchedule
            DateTime
            DeviceObjectReference
            DeviceObjectPropertyReference
            EventTransitionBits

            LimitEnable
            LogRecord

            ObjectPropertyReference
            ObjectTypesSupported
            PriorityValue
            PropertyReference
            PropertyValue

            ReadAccessResult
            ReadAccessResult$Result
            ReadAccessSpecification
            Recipient
            SequenceOf
            ServicesSupported
            SetpointReference
            ShedLevel
            StatusFlags
            TimeStamp
            TimeValue
            WriteAccessSpecification]
           [java.util ArrayList List]))


(c/enumerated-converter AccessRule$TimeRangeSpecifier)
(c/enumerated-converter AccessRule$LocationSpecifier)


(defn c-access-rule [value]
  (let [{:keys [time-range-specifier time-range location-specifier location enable]
         :or {time-range-specifier :always
              time-range nil
              location-specifier :all
              location nil
              enable true}} value]
    (AccessRule. ;(clojure->bacnet :time-range-specifier time-range-specifier) ;;<- only for private constructor
     (clojure->bacnet :device-object-property-reference time-range)
                                        ;(clojure->bacnet :location-specifier location-specifier) ;;<- only for private constructor
     (clojure->bacnet :device-object-reference location)
     (clojure->bacnet :boolean enable))))

(defmethod clojure->bacnet :access-rule
  [_ value]
  (c-access-rule value))


(defmethod bacnet->clojure AccessRule
  [^AccessRule o]
  {
                                        ;:time-range-specifier (bacnet->clojure (.getTimeRangeSpecifier o))
   :time-range (bacnet->clojure (.getTimeRange o))
                                        ;:location-specifier (bacnet->clojure (.getLocationSpecifier o))
   :location (bacnet->clojure (.getLocation o))
   :enable (bacnet->clojure (.getEnable o))})

;;;



(c/enumerated-converter AccumulatorRecord$AccumulatorStatus)

(defn c-accumulator-record [value]
  (let [{:keys [timestamp present-value accumulated-value accumulator-status]} value]
    (AccumulatorRecord. (clojure->bacnet :date-time timestamp)
                        (clojure->bacnet :unsigned-integer present-value)
                        (clojure->bacnet :unsigned-integer accumulated-value)
                        (clojure->bacnet :accumulator-status accumulator-status))))

(defmethod clojure->bacnet :accumulator-record
  [_ value]
  (c-accumulator-record value))

(defmethod bacnet->clojure AccumulatorRecord
  [^AccumulatorRecord o]
  {:timestamp (bacnet->clojure (.getTimestamp o))
   :present-value (bacnet->clojure (.getPresentValue o))
   :accumulated-value (bacnet->clojure (.getAccumulatedValue o))
   :accumulator-status (bacnet->clojure (.getAccumulatorStatus o))})

;;;

(defn c-action-command [{:keys [device-identifier object-identifier property-identifier
                                property-array-index property-value priority
                                post-delay quit-on-failure write-successful]}]
  (ActionCommand. (clojure->bacnet :object-identifier device-identifier)
                  (clojure->bacnet :object-identifier  object-identifier)
                  (clojure->bacnet :property-identifier property-identifier)
                  (clojure->bacnet :unsigned-integer property-array-index)
                  (clojure->bacnet :property-value*  {:object-type (or (first object-identifier) :analog-input)
                                                      :property-identifier (or property-identifier :present-value)
                                                      :value (last property-value)
                                                      :priority priority
                                                      :property-array-index property-array-index})
                  (clojure->bacnet :unsigned-integer priority)
                  (clojure->bacnet :unsigned-integer post-delay)
                  (clojure->bacnet :boolean quit-on-failure)
                  (clojure->bacnet :boolean write-successful)))

(defmethod clojure->bacnet :action-command
  [_ value]
  (c-action-command value))

(defmethod bacnet->clojure ActionCommand
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

;;;


(defn c-action-list [coll]
  (clojure->bacnet :sequence-of
                   (map (partial clojure->bacnet :action-command)
                        (or coll [nil]))))

(defmethod clojure->bacnet :action-list
  [_ value]
  (c-action-list value))

(defmethod bacnet->clojure ActionList
  [^ActionList o]
  (bacnet->clojure (.getAction o)))

;;;

(defmethod clojure->bacnet :address
  [_ value]
  (let [{:keys [mac-address network-number]} value]
    (Address. network-number (clojure->bacnet :octet-string mac-address))))

(defmethod bacnet->clojure Address
  [^Address o]
  {:mac-address (bacnet->clojure (.getMacAddress o))
   :network-number (bacnet->clojure (.getNetworkNumber o))})


;; ONE WAY

(defmethod bacnet->clojure Choice
  [^Choice o]
  (bacnet->clojure (.getDatum o)))

;;;

(defn c-daily-schedule [value]
  (DailySchedule.
   (clojure->bacnet :sequence-of
                    (map #(clojure->bacnet :time-value %) value))))

(defmethod clojure->bacnet :daily-schedule
  [_ value]
  (c-daily-schedule
   (or value [{:time "17:35:1.224" :value 10.2}])))

(defmethod bacnet->clojure DailySchedule
  [^DailySchedule o]
  (bacnet->clojure (.getDaySchedule o)))


;;;

(defn c-date-time [string]
  (if-not string
    (DateTime. (java.util.GregorianCalendar.))
    (let [t (tl/to-local-date-time
             (tf/parse
              (tf/formatters :date-time) string))
          gregorian-calendar (.toGregorianCalendar t)]
      (DateTime. (com.serotonin.bacnet4j.type.primitive.Date. gregorian-calendar)
                 (com.serotonin.bacnet4j.type.primitive.Time. gregorian-calendar)))))

(defmethod clojure->bacnet :date-time
  [_ value]
  (c-date-time value))


(defmethod bacnet->clojure DateTime
  [^DateTime o]
  (let [date (.getDate o)
        time (.getTime o)]
    ;; we don't handle unspecified time for now.
    (str (clj-time.core/date-time (.getCenturyYear date) ;; unspecified = 255
                                  (max (.getId (.getMonth date)) 1) ;; unspecified default to 1
                                  (let [day (.getDay date)]
                                    (if (= day com.serotonin.bacnet4j.type.primitive.Date/UNSPECIFIED_DAY)
                                      1 day)) ;; unspecified default to 1
                                  (if (.isHourUnspecified time) 0
                                      (.getHour time))
                                  (if (.isMinuteUnspecified time) 0
                                      (.getMinute time))
                                  (if (.isSecondUnspecified time) 0
                                      (.getSecond time))
                                  (if (.isHundredthUnspecified time) 0
                                      (* (.getHundredth time) 10))))))



;;;

(defn c-property-reference
  "Make a property reference. Argument can be of the forms:
   [<property-identifer> <array-index>]
   <property-identifier>"
  [property-reference]
  (let [[property-identifier array-index]
        (if (coll? property-reference)
          property-reference [property-reference])]
    (if array-index
      (PropertyReference. (clojure->bacnet :property-identifier property-identifier)
                          (clojure->bacnet :unsigned-integer array-index))
      (PropertyReference. (clojure->bacnet :property-identifier property-identifier)))))


(defmethod clojure->bacnet :property-reference
  [_ value]
  (c-property-reference value))

(defmethod bacnet->clojure PropertyReference
  [^PropertyReference o]
  (let [property-array-index (bacnet->clojure (.getPropertyArrayIndex o))]
    (if-not property-array-index
      (bacnet->clojure (.getPropertyIdentifier o))
      [(bacnet->clojure (.getPropertyIdentifier o)) property-array-index])))

;;;

;; Property-value is a nasty beast. We can't encode the value without
;; knowing its data-type, which we can't know here because we don't
;; have the object-type. The possible solutions are to pre-encode the
;; value, or to somehow pass the 'type' with the arguments.

(defn c-property-value* [m]
  (let [{:keys [property-identifier property-array-index value priority]
         :or {value (clojure->bacnet :unsigned-integer nil)}} m] ;; <-- allow quick object generation with empty map
    (when-not (instance? com.serotonin.bacnet4j.type.Encodable value)
      (throw (Exception. (str "The value for "property-identifier
                              " must already be converted into a bacnet4j object."))))
    (PropertyValue. (clojure->bacnet :property-identifier property-identifier)
                    (when property-array-index
                      (clojure->bacnet :unsigned-integer property-array-index))
                    value
                    (when priority (clojure->bacnet :unsigned-integer priority)))))

(defn c-simple-property-value [vectors]
  (if (nil? vectors)
    (c-property-value* nil)
    (let [[property-identifier value] vectors]
      (c-property-value* {:property-identifier property-identifier
                          :value value}))))

(defn c-property-value
  "Encode the property-value object. The value must already be a bacnet4j object.

  Accept 2 forms. The simple form is the one usually returned when
  converting the bacnet4j object into a clojure datastructure.

  The complex:
  - {:property-identifier ...,
     :property-array-index ...,
     :value ...,
     :priority ...}

  Or simple:
  - [property-identifier value]"[m]
  (if (map? m)
    (c-property-value* m)
    (c-simple-property-value m)))


(defmethod clojure->bacnet :property-value*
  [_ m]
  (let [{:keys [object-type property-identifier value priority]
         :or {object-type :analog-input property-identifier :present-value value 0}} m
        encoded-value (obj/encode-property-value object-type property-identifier
                                                 (if (nil? value)
                                                   (obj/force-type value :null) value))]
    (c-property-value {:property-identifier property-identifier
                       :value encoded-value
                       :priority priority})))


;; Another though one. Most of the time, users won't care about the
;; property-array-index or the priority when reading a value. As such,
;; we'll just return a vector with the property reference and the
;; value.

;; However, we will allow the possibility of returning a detailed
;; property-value using a dynamic var.

(def ^:dynamic *detailed-property-value*
  "If true, conversions from bacnet4j will include the
  property-array-index and the value's priority." false)

(defmacro with-detailed-property-value
  "Property-value(s), instead of being converted to
   '[property-identifier value]'

   Will get some additional values:
   {:property-identifier ...,
    :property-array-index ...,
    :value ...,
    :priority ...}."
  [& body]
  `(binding [*detailed-property-value* true]
     (do ~@body)))

(defmethod bacnet->clojure PropertyValue
  [^PropertyValue o]
  (let [p-id (bacnet->clojure (.getPropertyIdentifier o))
        p-array-index (bacnet->clojure (.getPropertyArrayIndex o))
        value (bacnet->clojure (.getValue o))
        priority (bacnet->clojure (.getPriority o))]
    (if *detailed-property-value*
      {:property-identifier p-id
       :property-array-index p-array-index
       :value value
       :priority priority}
      [p-id value])))


;;;


;; one way only for now

(defmethod bacnet->clojure PriorityValue
  [^PriorityValue o]
  (c/bacnet->clojure (.getValue o)))


;;;



(defn c-device-object-reference
  [[device-identifier object-identifier]]
  (DeviceObjectReference. (clojure->bacnet :object-identifier device-identifier)
                          (clojure->bacnet :object-identifier object-identifier)))

(defmethod clojure->bacnet :device-object-reference
  [_ value]
  (if (nil? value)
    (c-device-object-reference [value value])
    (c-device-object-reference value)))

(defmethod bacnet->clojure DeviceObjectReference
  [^DeviceObjectReference o]
  [(bacnet->clojure (.getDeviceIdentifier o))
   (bacnet->clojure (.getObjectIdentifier o))])



;;;

(defn c-event-transition-bits
  [{:keys [to-fault to-normal to-offnormal]
    :or {to-fault false to-normal false to-offnormal false}}]
  (EventTransitionBits. to-offnormal to-fault to-normal))

(defmethod clojure->bacnet :event-transition-bits
  [_ value]
  (c-event-transition-bits value))

(defmethod bacnet->clojure EventTransitionBits
  [^EventTransitionBits o]
  {:to-fault (.isToFault o)
   :to-normal (.isToNormal o)
   :to-offnormal (.isToOffnormal o)})


;;;

(defn c-limit-enable
  [{:keys [high-limit-enable low-limit-enable]
    :or {high-limit-enable false low-limit-enable false}}]
  (LimitEnable. low-limit-enable high-limit-enable))

(defmethod clojure->bacnet :limit-enable
  [_ value]
  (c-limit-enable value))

(defmethod bacnet->clojure LimitEnable
  [^LimitEnable o]
  {:high-limit-enable (.isHighLimitEnable o)
   :low-limit-enable (.isLowLimitEnable o)})



;;;

(defn c-log-record
  [{:keys [timestamp type value status-flags]
    :or {type :real value 0}}]
  (LogRecord. (clojure->bacnet :date-time timestamp)
              (clojure->bacnet type value)
              (clojure->bacnet :status-flags status-flags)))

(defmethod clojure->bacnet :log-record
  [_ value]
  (c-log-record value))

(defmethod bacnet->clojure LogRecord
  [^LogRecord o]
  (let [value (.getChoice o)]
    {:timestamp (bacnet->clojure (.getTimestamp o))
     :status-flags (bacnet->clojure (.getStatusFlags o))
     :type (c/class-to-keyword (class value))
     :value (bacnet->clojure value)}))



;;;


(defn c-object-property-reference [[object-identifier property-reference]]
  (let [prop-ref (clojure->bacnet :property-reference property-reference)]
    (ObjectPropertyReference. (clojure->bacnet :object-identifier object-identifier)
                              (.getPropertyIdentifier prop-ref)
                              (.getPropertyArrayIndex prop-ref))))

(defmethod clojure->bacnet :object-property-reference
  [_ value]
  (c-object-property-reference value))


(defmethod bacnet->clojure ObjectPropertyReference
  [^ObjectPropertyReference o]
  (let [oid (bacnet->clojure (.getObjectIdentifier o))]
    [oid
     (->> [(.getPropertyIdentifier o)(.getPropertyArrayIndex o)]
          (map bacnet->clojure)
          (clojure->bacnet :property-reference)
          bacnet->clojure)]))


;;;

(defn c-device-object-property-reference
  [[device-object-identifier object-property-reference]]
  (let [prop-ref (clojure->bacnet :property-reference (last object-property-reference))]
    (DeviceObjectPropertyReference.
     (clojure->bacnet :object-identifier (first object-property-reference))
     (.getPropertyIdentifier prop-ref)
     (.getPropertyArrayIndex prop-ref)
     (clojure->bacnet :object-identifier device-object-identifier))))

(defmethod clojure->bacnet :device-object-property-reference
  [_ value]
  (if (nil? value)
    (c-device-object-property-reference [value value])
    (c-device-object-property-reference value)))

(defmethod bacnet->clojure DeviceObjectPropertyReference
  [^DeviceObjectPropertyReference o]
  (let [data {:device-identifier (bacnet->clojure (.getDeviceIdentifier o))
              :object-identifier (bacnet->clojure (.getObjectIdentifier o))
              :property-array-index (bacnet->clojure (.getPropertyArrayIndex o))
              :property-identifier (bacnet->clojure (.getPropertyIdentifier o))}]
    [(:device-identifier data)
     (-> (clojure->bacnet :object-property-reference
                          [(:object-identifier data)
                           [(:property-identifier data)
                            (:property-array-index data)]])
         bacnet->clojure)]))



(defn c-read-access-specification
  "As property-references, will accept:
   <property-identifier>
   [<property-identifier> <property-identifier>]
   [[<property-identifier> <array-index>] [<property-identifier> <array-index>]]"
  [object-identifier property-references]
  (ReadAccessSpecification.
   (clojure->bacnet :object-identifier object-identifier)
   (clojure->bacnet
    :sequence-of
    (mapv (partial clojure->bacnet :property-reference) property-references))))

(defmethod clojure->bacnet :read-access-specification
  [_ value]
  (let [object-identifier (first value)
        property-references (or (seq (rest value)) [0 1])]
    (c-read-access-specification object-identifier
                                 property-references)))


(defmethod bacnet->clojure ReadAccessSpecification
  [^ReadAccessSpecification o]
  (vec (cons (bacnet->clojure (.getObjectIdentifier o))
             (bacnet->clojure (.getListOfPropertyReferences o)))))


;;;

;; ONE WAY


(defmethod bacnet->clojure ReadAccessResult
  [^ReadAccessResult o]
  (let [oid (.getObjectIdentifier o)]
    (for [result (bacnet->clojure (.getListOfResults o))]
      (into {}
            [[:object-identifier (bacnet->clojure oid)]
             result]))))

;;;


;; ONE WAY

(defmethod bacnet->clojure ReadAccessResult$Result
  [^ReadAccessResult$Result o]
  (let [prop-ref (->> (map bacnet->clojure [(.getPropertyIdentifier o)
                                            (.getPropertyArrayIndex o)])
                      (clojure->bacnet :property-reference)
                      bacnet->clojure)]
    (try {prop-ref
          (bacnet->clojure (.getReadResult o))}
         (catch Exception e (do (println (str "Error : " (.getMessage e) " --- " prop-ref ))
                                {prop-ref {:error {:error-message (.getMessage e)}}})))))

;;;

(defn c-recipient [v]
  (-> (if (map? v)
        (clojure->bacnet :address v)
        (clojure->bacnet :object-identifier v))
      (Recipient.)))

(defmethod clojure->bacnet :recipient
  [_ value]
  (c-recipient (or value [:analog-input 0])))

(defmethod bacnet->clojure Recipient
  [^Recipient o]
  (cond (.isAddress o)
        (bacnet->clojure (.getAddress o))
        (.isDevice o)
        (bacnet->clojure (.getDevice o))))

;;;


(defmethod clojure->bacnet :sequence-of
  [_ value]
  (SequenceOf. (ArrayList. (or value []))))

(defmethod bacnet->clojure SequenceOf
  [^SequenceOf o]
  (into [] (map bacnet->clojure o)))



;;;

(def services-supported
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
   [:get-event-information 39]
   [:write-group 40]
   ;;services new in bacnet4j 4.0.0
   [:subscribe-cov-multiple 41]
   [:confirmed-cov-notification-multiple 42]
   [:unconfirmed-cov-notification-multiple 43]
   ])

(defn coerce-supported [java-object smap]
  (doseq [item smap]
    (try (clojure.lang.Reflector/invokeInstanceMethod
          java-object
          (str "set" (c/to-camel (str/capitalize (name (key item)))))
          (to-array [(val item)]))
         (catch Exception e)))
  java-object)

(defn c-services-supported [smap]
  (let [services-supported (ServicesSupported.)]
    (coerce-supported services-supported smap)))

(defmethod clojure->bacnet :services-supported
  [_ value]
  (c-services-supported value))

(defmethod bacnet->clojure ServicesSupported
  [^ServicesSupported o]
  (->> (seq (.getValue o))
       (interleave (map first services-supported))
       (apply hash-map)))


;;;

(defn c-setpoint-reference [object-property-reference]
  (SetpointReference.
   (clojure->bacnet :object-property-reference object-property-reference)))

(defmethod clojure->bacnet :setpoint-reference
  [_ value]
  (c-setpoint-reference value))

(defmethod bacnet->clojure SetpointReference
  [^SetpointReference o]
  (bacnet->clojure (.getSetpointReference o)))


;;;


;; (defn c-shed-level [v]
;;   (if-let [r (:real v)]
;;     (ShedLevel. (clojure->bacnet :real r))
;;     (ShedLevel. (clojure->bacnet :unsigned-integer (:level v))
;;                 ;(clojure->bacnet :boolean (:percent v))
;;                 ;; not a BACnet boolean?!
;;                 (:percent v))))

;; (defmethod clojure->bacnet :shed-level
;;   [_ value]
;;   (c-shed-level (or value {:level 12 :percent true})))

;; FFS... both .getLevel and .getPercent return the same value. At
;; first look there's no way of knowing if it's a level or percent.
;; This means we can't re-encode with certainty.

(defmethod bacnet->clojure ShedLevel
  [^ShedLevel o]
  (or (try {:real (bacnet->clojure (.getAmount o))}
           (catch Exception e))
      {:level (bacnet->clojure (.getLevel o))
       :percent (bacnet->clojure (.getPercent o))}))


;;;

(defn c-status-flags [{:keys[in-alarm fault overridden out-of-service]
                       :or {in-alarm false fault false overridden false out-of-service false}}]
  (StatusFlags. in-alarm fault overridden out-of-service))

(defmethod clojure->bacnet :status-flags
  [_ value]
  (c-status-flags value))

(defmethod bacnet->clojure StatusFlags
  [^StatusFlags o]
  {:in-alarm (.isInAlarm o)
   :fault (.isFault o)
   :out-of-service (.isOutOfService o)
   :overridden (.isOverridden o)})
;;;

(def object-types-supported
  (sort-by last e/object-type-map))

(defn simple-coerce-supported [java-object smap]
  (doseq [item smap]
    (let [[k v] item]
      (.set java-object (clojure->bacnet :object-type k) v)))
  java-object)

(defn c-object-types-supported [smap]
  (let [object-types-supported (ObjectTypesSupported.)]
    (simple-coerce-supported object-types-supported smap)))

(defmethod clojure->bacnet :object-types-supported
  [_ value]
  (c-object-types-supported value))

(defmethod bacnet->clojure ObjectTypesSupported
  [^ObjectTypesSupported o]
  (->> (seq (.getValue o))
       (interleave (map first object-types-supported))
       (apply hash-map)))


;;;

(defn c-time-value [value]
  (let [{:keys [time value]} value]
    (TimeValue. (clojure->bacnet :time time)
                (p/c-primitive value))))

(defmethod clojure->bacnet :time-value
  [_ value]
  (c-time-value value))

(defmethod bacnet->clojure TimeValue
  [^TimeValue o]
  {:time (bacnet->clojure (.getTime o))
   :value (bacnet->clojure (.getValue o))})

;;;

(defmethod bacnet->clojure TimeStamp
  [^TimeStamp o]
  (cond (.isDateTime o) (bacnet->clojure (.getDateTime o))
        (.isTime o) (bacnet->clojure (.getTime o))
        (.isSequenceNumber o) (bacnet->clojure (.getSequenceNumber o))
        :else (throw (Exception. "The time-stamp isn't in a BACnet date-time format."))))

(defmethod clojure->bacnet :time-stamp
  [_ value]
  (or (when (number? value) (TimeStamp. (clojure->bacnet :unsigned-integer value)))
      (try (when-let [date-time (clojure->bacnet :date-time value)]
             (TimeStamp. date-time))
           (catch Exception e))
      (TimeStamp. (clojure->bacnet :time value))))


(defmethod bacnet->clojure WriteAccessSpecification
  [^WriteAccessSpecification o]
  [(c/bacnet->clojure (.getObjectIdentifier o))
   (mapv c/bacnet->clojure (.getListOfProperties o))])

(defn- property-value->map
  [pv]
  (if (vector? pv)
    (let [[p-id value] pv]
      (if (and (map? value) (contains? value :value))
        (assoc value :property-identifier p-id)
        {:property-identifier p-id
         :value               value}))
    pv))

(defn- get-property-write-specs
  [oid properties]
  (mapv #(c/clojure->bacnet :property-value*
                            (merge (property-value->map %)
                                   {:object-type (first oid)}))
        properties))

(defmethod clojure->bacnet :write-access-specification
  [_ value]
  (let [[oid properties] value]
    (WriteAccessSpecification.
     (c/clojure->bacnet :object-identifier oid)
     (c/clojure->bacnet :sequence-of (get-property-write-specs oid properties)))))

;; (defn c-property-reference
;;   "Make a property reference. Argument can be of the forms:
;;    [<property-identifer> <array-index>]
;;    <property-identifier>"
;;   [property-reference]
;;   (let [[property-identifier array-index]
;;         (if (coll? property-reference)
;;           property-reference [property-reference])]
;;     (PropertyReference. (c-property-identifier property-identifier)
;;                         (c-unsigned array-index))))


;; (defn c-object-property-reference [[object-identifier property-reference]]
;;   (let [prop-ref (c-property-reference property-reference)]
;;     (ObjectPropertyReference. (c-object-identifier object-identifier)
;;                               (.getPropertyIdentifier prop-ref)
;;                               (.getPropertyArrayIndex prop-ref))))

                                        ; DeviceObjectPropertyReference


;;;

;; (declare c-date-time)
;; (defn c-accumulator [m]
;;   (let [{:keys [timestamp present-value accumulated-value accumulator-status]} m]
;;     (AccumulatorRecord. (c-date-time timestamp)
;;                         (c-unsigned present-value)
;;                         (c-unsigned accumulated-value)
;;                         (c-accumulator-status accumulator-status))))

;; (defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.AccumulatorRecord
;;   [^AccumulatorRecord o]
;;   {:timestamp (bacnet->clojure (.getTimestamp o))
;;    :present-value (bacnet->clojure (.getPresentValue o))
;;    :accumulated-value (bacnet->clojure (.getAccumulatedValue o))
;;    :accumulator-status (bacnet->clojure (.getAccumulatorStatus o))})

;; ;;;


;; (def accumulator-status-map
;;   (c/subclass-to-map AccumulatorRecord$AccumulatorStatus.))

;; (defn c-accumulator-status [value]
;;   (let [subclass-map accumulator-status-map]
;;     (AccumulatorRecord$AccumulatorStatus. (or (c/map-or-num subclass-map value) 0))))

;; (defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.AccumulatorRecord$AccumulatorStatus
;;   [^AccumulatorRecord$AccumulatorStatus o]
;;   (first (->> accumulator-status-map
;;               (filter (comp #{(.intValue o)} val))
;;               (map key))))
