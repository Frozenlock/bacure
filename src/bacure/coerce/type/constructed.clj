(ns bacure.coerce.type.constructed
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.primitive :as p]
            [bacure.coerce.type.primitive :as e]
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
            BACnetError
            Choice
            DateTime
            DeviceObjectReference
            DeviceObjectPropertyReference

            
            PropertyReference
            PropertyValue
            ObjectPropertyReference
            ObjectTypesSupported

            ReadAccessResult
            ReadAccessResult$Result
            ReadAccessSpecification
            SequenceOf
            ServicesSupported
            StatusFlags]
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

;; ONE WAY

(defmethod bacnet->clojure BACnetError
  [^BACnetError o]
  {:error {:error-class (bacnet->clojure (.getErrorClass o))
           :error-code (bacnet->clojure (.getErrorCode o))}})

;; (defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.BACnetError
;;   [^BACnetError o]
;;   (throw (Exception. (.toString o))))

;;;

;; ONE WAY

(defmethod bacnet->clojure Choice
  [^Choice o]
  (bacnet->clojure (.getDatum o)))

;;;

(defn c-date-time [string]
  (if-not string
    (DateTime.)
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
    (str (clj-time.core/date-time (.getCenturyYear date)
                                  (.getId (.getMonth date))
                                  (.getDay date)
                                  (.getHour time)
                                  (.getMinute time)
                                  (.getSecond time)
                                  (* (.getHundredth time) 10)))))



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

;; To prevent any confusion, we will not make this conversion
;; available to the `clojure->bacnet' method.

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
  (let [{:keys [object-type property-identifier value]
         :or {object-type :analog-input property-identifier :present-value value 0}} m
        encoded-value (obj/encode-property-value object-type property-identifier value)]
    (c-property-value {:property-identifier property-identifier
                       :value encoded-value})))


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

;;;

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
         (catch Exception e (do (println (str (.getMessage e) " --- " prop-ref ))
                                {prop-ref nil})))))

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
   [:write-group 40]])

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
  [[:analog-input 0]
   [:analog-output 1]
   [:analog-value 2]
   [:binary-input 3]
   [:binary-output 4]
   [:binary-value 5]
   [:calendar 6]
   [:command 7]
   [:device 8]
   [:event-enrollment 9]
   [:file 10]
   [:group 11]
   [:loop 12]
   [:multi-state-input 13]
   [:multi-state-output 14]
   [:notification-class 15]
   [:program 16]
   [:schedule 17]
   [:averaging 18]
   [:multi-state-value 19]
   [:trend-log 20]
   [:life-safety-point 21]
   [:life-safety-zone 22]
   [:accumulator 23]
   [:pulse-converter 24]
   [:event-log 25]
   [:trend-log-multiple 27]
   [:load-control 28]
   [:structured-view 29]
   [:access-door 30]])

(defn c-object-types-supported [smap]
  (let [object-types-supported (ObjectTypesSupported.)]
    (coerce-supported object-types-supported smap)))

(defmethod clojure->bacnet :object-types-supported
  [_ value]
  (c-object-types-supported value))

(defmethod bacnet->clojure ObjectTypesSupported
  [^ObjectTypesSupported o]
  (->> (seq (.getValue o))
       (interleave (map first object-types-supported))
       (apply hash-map)))



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
