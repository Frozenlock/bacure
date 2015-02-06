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
            service.confirmed.ReadRangeRequest$ByPosition
            service.confirmed.ReadRangeRequest$BySequenceNumber
            service.confirmed.ReadRangeRequest$ByTime
            service.unconfirmed.WhoIsRequest
            type.AmbiguousValue
            type.constructed.AccumulatorRecord
            type.constructed.AccumulatorRecord$AccumulatorStatus
            type.constructed.ActionCommand
            type.constructed.ActionList
            type.constructed.Address
            type.constructed.BACnetError
            type.constructed.Choice
            type.constructed.CovSubscription
            type.constructed.Destination
            type.constructed.DeviceObjectPropertyReference
            type.constructed.EventTransitionBits            
            type.constructed.PriorityArray
            type.constructed.PriorityValue
            type.constructed.PropertyReference
            type.constructed.PropertyValue            
            type.constructed.LimitEnable
            type.constructed.LogRecord
            type.constructed.ReadAccessSpecification
            type.constructed.Recipient
            type.constructed.SequenceOf
            type.constructed.SetpointReference
            type.constructed.WriteAccessSpecification
            type.constructed.DateTime
            type.constructed.ObjectPropertyReference
            type.constructed.ObjectTypesSupported
            type.constructed.TimeStamp
            type.constructed.ReadAccessSpecification
            type.constructed.ReadAccessResult
            type.constructed.ReadAccessResult$Result
            type.constructed.StatusFlags
            type.constructed.ServicesSupported
            type.constructed.ShedLevel
            type.enumerated.BinaryPV
            type.enumerated.DeviceStatus
            type.enumerated.EngineeringUnits            
            type.enumerated.EventState
            type.enumerated.NotifyType
            type.enumerated.ObjectType
            type.enumerated.Polarity
            type.enumerated.PropertyIdentifier
            type.enumerated.Segmentation
            type.enumerated.ShedState
            type.enumerated.Reliability
            type.primitive.CharacterString
            type.primitive.ObjectIdentifier
            type.primitive.Real
            type.primitive.UnsignedInteger
            type.primitive.Unsigned16
            type.primitive.SignedInteger
            type.primitive.Date
            type.primitive.Time
            util.PropertyReferences
            util.PropertyValues
            obj.BACnetObject
            obj.PropertyTypeDefinition
            obj.ObjectProperties)
           (java.util ArrayList List)))

;; In this part of the code we are doing conversions between the
;; Java/BACnet datatypes (real, object-identifier,
;; character-string...) and the usual Clojure ones. This is done to
;; abstract away knowledge that isn't useful to the end user.

;; In simpler terms, a user can simply enter the number '10' and we
;; will convert it automatically to the correct datatype (real,
;; unsigned-16...) before sending it on the network.


(def ^:dynamic *drop-ambiguous*
  "Ambiguous values will be dropped unless this is bound to false." false)

(def ^:dynamic *verbose*
  "Print some message when errors or special cases happen, but are
  discarded. Mostly for development purposes." false)



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
  (let [fields (.getDeclaredFields class)]
    (->> (for [f fields]
           (try [(-> (.getName f)
                     from-camel
                     string-name-to-keyword)
                 (.intValue (.get f nil))]
                (catch Exception e)))
         (remove nil?)
         (into {}))))

(def prop-int-map
  (merge (subclass-to-map PropertyIdentifier)
         ;; somehow there's some properties missing...
         {:backup-and-restore-state 338}))

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

(def device-status-map
  "The .toString method doesn't return the name, but instead the
  string version of an integer. Thus we have to hardcode the names."
  {:backup-in-progress 5
   :download-in-progress 3
   :download-required 2
   :non-operational 4
   :operational 0
   :operational-read-only 1})

(def accumulator-status-map
  "The .toString method doesn't return the name, but instead the
  string version of an integer. Thus we have to hardcode the names."
  {:normal 0
   :starting 1
   :recovered 2
   :abnormal 3
   :failed 4})

(def polarity-map
  "The .toString method doesn't return the name, but instead the
  string version of an integer. Thus we have to hardcode the names."
  {:normal 0
   :reverse 1})

(def shed-state-map
  "The .toString method doesn't return the name, but instead the
  string version of an integer. Thus we have to hardcode the names."
  {:compliant 2
   :inactive 0
   :non-compliant 3
   :request-pending 1})

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


;; (defn ambiguous [byte-seq]
;;   (proxy [com.serotonin.bacnet4j.type.Encodable] [byte-seq]
;;     (toString [] (str byte-seq))))


;;   (let [ byte-state (ref byte-seq)]
;;     (proxy [ java.io.InputStream] []
;;       (read [] 
;;         (dosync
;;           ;; peel off one byte to return and save the rest
;;           (let [[ b & more-bytes] @byte-state]
;;             (ref-set byte-state more-bytes)
;;             (if b b -1)))))))
   
;; (defn ambiguous

;;================================================================
;; Coerce to various datatypes functions
;;================================================================

(defn map-or-num [map key-or-num]
  (if (keyword? key-or-num)
    (if-let [[_ n] (re-find #"unknown-(\d+)" (name key-or-num))]
      (read-string n)
      (get map key-or-num))
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
  (PropertyIdentifier.
   (if (keyword? prop-keyword)
     (if-let [[_ n] (re-find #"unknown-(\d+)" (name prop-keyword))]
       (read-string n)
       (get prop-int-map prop-keyword))
     prop-keyword)))

(defn c-polarity [value]
  (Polarity. (or (map-or-num polarity-map value) 0)))

(defn c-real [value]
  (Real. (float value)))

(defn c-character-string [value]
  (CharacterString. value))

(defn c-status-flags [{:keys[in-alarm fault overridden out-of-service]
                      :or {in-alarm false fault false overridden false out-of-service false}}]
  (StatusFlags. in-alarm fault overridden out-of-service))

(defn c-status-flags [{:keys[in-alarm fault overridden out-of-service]
                      :or {in-alarm false fault false overridden false out-of-service false}}]
  (StatusFlags. in-alarm fault overridden out-of-service))

(defn c-shed-state [value]
  (ShedState. (or (map-or-num shed-state-map value) 0)))

(defn c-event-state [value]
  (let [subclass-map (subclass-to-map EventState)]
    (EventState. (get subclass-map value 0))))

(defn c-device-status [value]
  (DeviceStatus. (or (map-or-num device-status-map value) 4)))

(defn c-reliability [value]
  (let [subclass-map reliability-map]
    (Reliability. (or (map-or-num subclass-map value) 7))))

(defn c-boolean [bool]
  (com.serotonin.bacnet4j.type.primitive.Boolean. bool))

(defn c-unsigned [value]
  (when value
    (UnsignedInteger. value)))

(defn c-unsigned16 [value]
  (Unsigned16. value))

(defn c-unsigned-integer [value]
  (c-unsigned value))

(defn c-signed [value]
  (when value
    (SignedInteger. value)))

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

(defn c-property-reference
  "Make a property reference. Argument can be of the forms:
   [<property-identifer> <array-index>]
   <property-identifier>"
  [property-reference]
  (let [[property-identifier array-index]
        (if (coll? property-reference)
          property-reference [property-reference])]
    (PropertyReference. (c-property-identifier property-identifier)
                        (c-unsigned array-index))))


(defn c-object-property-reference [[object-identifier property-reference]]
  (let [prop-ref (c-property-reference property-reference)]
    (ObjectPropertyReference. (c-object-identifier object-identifier)
                              (.getPropertyIdentifier prop-ref)
                              (.getPropertyArrayIndex prop-ref))))
                            
(defn c-device-object-property-reference
  [[device-object-identifier object-property-reference]]
  (let [prop-ref (c-property-reference (last object-property-reference))]
    (DeviceObjectPropertyReference.
     (c-object-identifier (first object-property-reference))
     (.getPropertyIdentifier prop-ref)
     (.getPropertyArrayIndex prop-ref)
     (c-object-identifier device-object-identifier))))

(defn c-setpoint-reference [object-property-reference]
  (SetpointReference.
   (c-object-property-reference object-property-reference)))

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


;; (defn c-bacnet-object [object-identifier]
;;   (com.serotonin.bacnet4j.obj.BACnetObject. 
;;    @bacure.local-device/local-device (c-object-identifier object-identifier)))

(defn c-binary-pv [binary-value]
  (BinaryPV. (if binary-value 1 0)))

(defn c-priority-value [priority-value]
  (PriorityValue. (c-binary-pv priority-value)))

(defn c-priority-array [priority-values]
  (PriorityArray. (into [] (map c-priority-value priority-values))))

(defn c-action-list [coll]
  (c-array c-action-command coll))

(defn c-read-access-specification
  "As property-references, will accept:
   <property-identifier>
   [<property-identifier> <property-identifier>]
   [[<property-identifier> <array-index>] [<property-identifier> <array-index>]]"
  [object-identifier & property-references]
   (ReadAccessSpecification.
    (c-object-identifier object-identifier)
    (c-array c-property-reference property-references)))


    


;;================================================================
;; Convert to Clojure objects
;;================================================================  

;; Might be wise to use multimethods here, instead of a protocol.

(defmulti bacnet->clojure class)

(defmethod bacnet->clojure :default [x]
  (print "No method YET to coerce " (.toString x)" into a clojure structure."))

;; methods for type 'acknowledgement'

(defmethod bacnet->clojure com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyAck
  [^ReadPropertyAck o]
  (let [property-identifier (.getPropertyIdentifier o)
        value (.getValue o)]
    (->> (if (= (class value) AmbiguousValue)
           (.convertTo value (class property-identifier))
           value)
         bacnet->clojure)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.service.acknowledgement.CreateObjectAck
  [^CreateObjectAck o]
  {:choice-Id (.getChoiceId o)
   :object-identifier (bacnet->clojure (.getObjectIdentifier o))})

(defmethod bacnet->clojure com.serotonin.bacnet4j.service.acknowledgement.ReadRangeAck
  [^ReadRangeAck o]
  {:object-identifier (bacnet->clojure (.getObjectIdentifier o))
   (bacnet->clojure (.getPropertyIdentifier o)) (bacnet->clojure (.getItemData o))}
  ;;should we add the resultflags?
  )



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

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.DeviceStatus
  [^DeviceStatus o]
  (first (->> device-status-map
              (filter (comp #{(.intValue o)} val))
              (map key))))

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

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.Choice
  [^Choice o]
  (bacnet->clojure (.getDatum o)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.DateTime
  [^DateTime o]
  (clj-time.format/unparse
     (clj-time.format/formatters :date-time)
     (clj-time.coerce/from-long (.getTimeMillis o))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.DeviceObjectPropertyReference
  [^DeviceObjectPropertyReference o]
  (let [data {:device-identifier (bacnet->clojure (.getDeviceIdentifier o))
              :object-identifier (bacnet->clojure (.getObjectIdentifier o))
              :property-array-index (bacnet->clojure (.getPropertyArrayIndex o))
              :property-identifier (bacnet->clojure (.getPropertyIdentifier o))}]
    [(:device-identifier data)
     (-> (c-object-property-reference (:object-identifier data)
                                      [(:property-identifier data)
                                       (:property-array-index data)])
         bacnet->clojure)]))
                                                      

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.EventTransitionBits
  [^EventTransitionBits o]
  {:to-normal (.isToNormal o)
     :to-fault (.isToFault o)
     :to-off-normal (.isToOffnormal o)})

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.LimitEnable
  [^LimitEnable o]
  {:low-limit-enable (.isLowLimitEnable o)
     :high-limit-enable (.isHighLimitEnable o)})


(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.LogRecord
  [^LogRecord o]
  [(bacnet->clojure (.getTimestamp o))
   (bacnet->clojure (.getEncodable o))])


(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ObjectTypesSupported
  [^ObjectTypesSupported o]
  (try ;; we only 'try' it, because I've found devices triggering a
       ;; ArrayIndexOutOfBoundsException for the bacnet4j library. It
       ;; might be possible to use .getValue to get the list of
       ;; booleans and reconstruct the object.
    (bean-map o)
    (catch Exception e)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ObjectPropertyReference
  [^ObjectPropertyReference o]
  (let [oid (bacnet->clojure (.getObjectIdentifier o))]
    [oid
     (->> [(.getPropertyIdentifier o)(.getPropertyArrayIndex o)]
          (map bacnet->clojure)
          (c-property-reference)
          bacnet->clojure)]))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.PriorityValue
  [^PriorityValue o]
  (.getIntegerValue o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.PropertyReference
  [^PropertyReference o]
  (if-let [property-array-index (bacnet->clojure (.getPropertyArrayIndex o))]
    [(bacnet->clojure (.getPropertyIdentifier o)) property-array-index]
    (bacnet->clojure (.getPropertyIdentifier o))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.PropertyValue
  [^PropertyValue o]
  (into [] (map bacnet->clojure [(.getPropertyIdentifier o)(.getValue o)])))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.TimeStamp
  [^TimeStamp o]
  (cond (.isDateTime o) (bacnet->clojure (.getDateTime o))
        :else (throw (Exception. "The time-stamp isn't in a BACnet date-time format."))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ReadAccessSpecification
  [^ReadAccessSpecification o]
  [(bacnet->clojure (.getObjectIdentifier o))
   (bacnet->clojure (.getListOfPropertyReferences o))])

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ReadAccessResult$Result
  [^ReadAccessResult$Result o]
  (let [prop-ref (-> (map bacnet->clojure [(.getPropertyIdentifier o)
                                           (.getPropertyArrayIndex o)])
                     c-property-reference
                     bacnet->clojure)]
    (try {prop-ref
          (bacnet->clojure (.getReadResult o))}
         (catch Exception e (do (when *verbose*
                                  (print (str (.getMessage e) " --- " prop-ref )))
                                {prop-ref nil})))))

;; (defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ReadAccessResult
;;   [^ReadAccessResult o]
;;   (let [oid (.getObjectIdentifier o)]
;;     (for [result (.getValues (.getListOfResults o))]
;;       (let [value (try (bacnet->clojure (.getDatum (.getReadResult result)))
;;                        (catch Exception e))
;;             property-reference
;;             (c-property-reference (map bacnet->clojure [(.getPropertyIdentifier result)
;;                                                         (.getPropertyArrayIndex result)]))]
;;         (into {}
;;               [[:object-identifier (bacnet->clojure oid)]
;;                [(bacnet->clojure property-reference) value]])))))


(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ReadAccessResult
  [^ReadAccessResult o]
  (let [oid (.getObjectIdentifier o)]
    (->> (for [result (.getListOfResults o)]
           {[:object-identifier (bacnet->clojure oid)]
            (bacnet->clojure o)})
         (filter #(nil? (val %)) ))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ReadAccessResult
  [^ReadAccessResult o]
  (let [oid (.getObjectIdentifier o)]
    (for [result (bacnet->clojure (.getListOfResults o))]
      (into {}
            [[:object-identifier (bacnet->clojure oid)]
             result]))))


(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.SequenceOf
  [^SequenceOf o]
  (into [] (map bacnet->clojure o)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.SetpointReference
  [^SetpointReference o]
  (bacnet->clojure (.getSetpointReference o)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ServicesSupported
  [^ServicesSupported o]
  (->> (seq (.getValue o))
       (interleave (map first services-list))
       (apply hash-map)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.ShedLevel
  [^ShedLevel o]
  (bacnet->clojure (.getLevel o)))

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
  (let [value (.floatValue o)]
    (when (== value value) ;; Sometimes the java.lang.float can be NaN (not a number).
      value)))             ;; The equality test can filter them out.

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

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.Polarity
  [^Polarity o]
  (first (->> polarity-map
              (filter (comp #{(.intValue o)} val))
              (map key))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier
  [^PropertyIdentifier o]
  (string-name-to-keyword o))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.ShedState
  [^ShedState o]
  (first (->> shed-state-map
              (filter (comp #{(.intValue o)} val))
              (map key))))

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
  (if-not *drop-ambiguous* o
          (do (when *verbose* (print "Ambiguous value dropped. See bacure.coerce for details."))
              nil))) ;; drop it! Ambiguous value was causing a lot of issues with
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


(defmethod bacnet->clojure clojure.lang.PersistentVector
  [^clojure.lang.PersistentVector o]
  (into [] (map bacnet->clojure o)))

;; methods for java

(defmethod bacnet->clojure java.util.ArrayList
  [^ArrayList o]
  (map bacnet->clojure (seq o)))

(defmethod bacnet->clojure java.lang.String
  [^String o]
  o)

;;================================================================

(defn object-profile
  "Given an object type, return the properties it should have, and if
   they are :required, :optional, or :sequence." [object-type]
   (->> (ObjectProperties/getRequiredPropertyTypeDefinitions
         (c-object-type object-type))
        (map bacnet->clojure)))

(defn properties-by-option
  "Return a list or properties. `option' should
  be :required, :optional, :sequence or :all."
  [object-type option]
  (let [profile (object-profile object-type)]
    (->> (if (= option :all) profile
             (filter (comp option last) profile))
         (map first))))

(defn determine-property-value-type 
  "Given an object-type and a property-identifier, return a map with the :type and if it's a :sequence."
  [object-type property-identifier]
  (let [type-def (ObjectProperties/getPropertyTypeDefinition
                  (c-object-type object-type) (c-property-identifier property-identifier))]
    {:type (->> (.getSimpleName (.getClazz type-def))
                from-camel
                str/lower-case)
     :sequence (.isSequence type-def)}))
;; We use the value type already associated to each property/object combo in the underlying Java library.
;; At this point building our own lookup table would be trivial, but there's no point in duplicating the work.

(defn get-object-type 
  "Find the object type in an object-map (either from
  the :object-type, or in the :object-identifier)."[obj-map]
  (or (:object-type obj-map) (first (:object-identifier obj-map))))

(defn encode-property 
  "Encode the property value depending on what type it should be given the object."
  [object-type property-identifier value]
  (let [value-type (determine-property-value-type object-type property-identifier)
        encode-fn (ns-resolve 'bacure.coerce
                              (symbol (str "c-" (:type value-type))))]
    (if (:sequence value-type)
      (c-array encode-fn value)
      (encode-fn value))))
;; We assume that the correct encoding function is the class name with
;; "c-" as a prefix. For example, for the class "real", the encoding
;; function would be "c-real".



(defn encode-properties-values
  "Take an object-map (a map of properties) and encode the properties
  into their native java object. For example, a clojure number \"1\"
  might be encoded into a real, or into an unisgned integer, depending
  on the object." [obj-map]
  (let [o-t (get-object-type obj-map)]
    (into {}
          (for [[property value] obj-map]
            (try 
              [property (encode-property o-t property value)]
              (catch Exception e (println (str "Could not encode *** " property 
                                               " ***. Might not be implemented yet."))))))))
       
(defn encode-properties
  "Encode an object map into a sequence of bacnet4j property-values.
  Remove-keys can be used to remove read-only properties before
  sending a command." [obj-map & remove-keys]
  (let [encoded-values-map (apply dissoc (cons (encode-properties-values obj-map) remove-keys))]
    (c-array #(c-property-value (c-property-identifier (key %)) (val %)) encoded-values-map)))

