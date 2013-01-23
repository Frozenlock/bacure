(ns bacure.core
  (:require [bacure.network :as network]
            [bacure.coerce :as coerce]))

(import '(com.serotonin.bacnet4j 
          LocalDevice 
          RemoteDevice 
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
          type.constructed.Address
          type.constructed.Destination
          type.constructed.EventTransitionBits
          type.constructed.PriorityArray
          type.constructed.PropertyReference
          type.constructed.PropertyValue
          type.constructed.ReadAccessSpecification
          type.constructed.Recipient
          type.constructed.SequenceOf
          type.constructed.WriteAccessSpecification
          type.constructed.DateTime
          type.constructed.TimeStamp
          type.enumerated.EngineeringUnits
          type.enumerated.ObjectType
          type.enumerated.PropertyIdentifier
          type.enumerated.Segmentation
          type.primitive.CharacterString
          type.primitive.ObjectIdentifier
          type.primitive.Real
          type.primitive.UnsignedInteger
          type.primitive.SignedInteger
          type.primitive.Date
          type.primitive.Time
          util.PropertyReferences
          ))

(defmacro mapify
  "Given some symbols, construct a map with the symbols as keys, and
  the value of the symbols as the map values. For example:
 (Let [aa 12]
     (mapify aa))
 => {:aa 12}"
  [& symbols]
  `(into {}
         (filter second
                 ~(into []
                        (for [item symbols]
                          [(keyword item) item])))))


(defn where
  "Use with `filter'"
  [criteria]
    (fn [m]
      (every? (fn [[k v]] (= (k m) v)) criteria)))

(defn new-local-device
  "Return a new configured BACnet local device . (A device is required
to communicate over the BACnet network.). To terminate it, use the
java method `terminate'. If needed, the initial configurations are
available in the metadata :config"
  [&[{:keys [device-id broadcast-address port local-address timeout]
      :or {device-id 1338 ;;some default configs
           broadcast-address (network/get-broadcast-address (network/get-ip))
           port 47808}}]]
  (let [ld (LocalDevice. device-id broadcast-address local-address)]
    (.setPort ld port)
    (.setTimeout ld (or timeout 20000)) ;; increase the default to 20s
    (def ^{:config (mapify device-id broadcast-address port local-address)}
      local-device ld))) ;;store in metadata.. less messy then querying the local-device

(new-local-device)
;create an initial local-device

(defn initialize
  "Initialize the local device. This will bind it to it's port (most
  likely 47808). The port will remain unavailable until the device is
  terminated. Once terminated, you should discard the device and
  create a new one if needed."[]
  (.initialize local-device))

(defn terminate
  "Terminate the local device, freeing any bound port in the process."[]
  (try (.terminate local-device)
       (catch Exception e))) ;if the device isn't initialized, it will throw an error



(defn find-remote-devices
  "We find remote devices by sending a 'WhoIs' broadcast. Every device
  that responds is added to the remote-devices field in the
  local-device."
  [&[{:keys [min-range max-range dest-port] :or {dest-port 47808}}]]
  (.sendBroadcast local-device
                  dest-port (if (and min-range max-range)
                              (WhoIsRequest.
                               (UnsignedInteger. min-range)
                               (UnsignedInteger. max-range))
                              (WhoIsRequest.))))


(defn local-device-backup
  "Spit all important information about the local device into a map." []
  (merge (:config (meta #'local-device))
         {:objects (local-objects)
          :port (.getPort local-device)
          :retries (.getRetries local-device)
          :seg-timeout (.getSegTimeout local-device)
          :seg-window (.getSegWindow local-device)
          :timeout (.getTimeout local-device)}))
;; eventually we should be able to add programs in the local device


(defn object-identifier
  "Make an object identifier from an object map"
  [&[{:keys [object-identifier]}]]
  (coerce/c-object-identifier object-identifier))


(defn add-object
  "Add a local object and return it."
  [object-map]
  (let [object-id (object-identifier object-map)]
    (try (.addObject local-device
                     (com.serotonin.bacnet4j.obj.BACnetObject. local-device object-id))
         (catch Exception e (print (str "caught exception: " (.getMessage e)))))
    (.getObject local-device object-id)))
  
(defn add-or-update-object
  "Update a local object properties. Create the object it if not
  already present. Will not try to modify any :object-type
  or :object-identifier." [object-map]
  (let [object-id (object-identifier object-map)
        b-obj (or (.getObject local-device object-id) (add-object object-map))]
    (doseq [prop (coerce/encode-properties object-map :object-identifier :object-type)]
      (.setProperty b-obj prop))
    (coerce/bacnet->clojure b-obj)))


(defn remove-object
  "Remove a local object"
  [object-map]
  (.removeObject local-device (object-identifier object-map)))

(defn flush-objects
  "Remove all local object"[]
  (doseq [o (local-objects)]
    (remove-object o)))


(defn object-name [device-id object-identifier]
  (.sendReadPropertyAllowNull local-device (rd device-id)
                              object-identifier
                              com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier/objectName))

(defn reset-local-device
  "Terminate the local device and discard it. Replace it with a new
  local device, and apply the same configurations as the previous one.
  If a map with new configurations is provided, it will be merge with
  the old config.

  (reset-local-device {:device-id 1112})
  ---> reset the device and change the device id."

  [& [new-config]]
  (let [configs (merge (local-device-backup) new-config)]
    (terminate)
    (new-local-device configs)
    (doto local-device
      (.setRetries (:retries configs))
      (.setSegTimeout (:seg-timeout configs))
      (.setSegWindow (:seg-window configs))
      (.setTimeout (:timeout configs)))
    (initialize)
    (doseq [o (:objects configs)]
      (add-or-update-object o))))




(defn get-remote-devices-and-info
  "Given a local device, sends a WhoIs. For every device discovered,
  get its extended information. Return the remote devices as a list."
  [&[{:keys [min-range max-range dest-port] :as args}]]
  (find-remote-devices args)
  (Thread/sleep 700) ;wait a little to insure we get the responses
  (let [rds (-> local-device (.getRemoteDevices))]
    (doseq [remote-device rds]
      (-> local-device (.getExtendedDeviceInformation remote-device)))
    (for [rd rds]
      (.getInstanceNumber rd))))


(defn remote-devices
  "Return the list of the current remote devices" []
  (for [rd (seq (.getRemoteDevices local-device))]
    (.getInstanceNumber rd)))


(defn rd
  "Get the remote device by its device-id"
  [device-id]
  (.getRemoteDevice local-device device-id))


(defn local-objects
  "Return a list of local objects"[]
  (map coerce/bacnet->clojure
       (.getLocalObjects local-device)))


(defn get-remote-object-identifiers
  "Return a remote device's object identifiers (object-list) as a
  sequence."
  [device-id]
  (let [remote-device (rd device-id)]
    (seq (.getValues (.sendReadPropertyAllowNull
                      local-device remote-device
                      (.getObjectIdentifier remote-device)
                      PropertyIdentifier/objectList)))))


;;;;;;;;;;;;;;;

(defn add-remote-object [device-id object-map]
  (.send local-device (rd device-id)
         (CreateObjectRequest. (object-identifier object-map)
                               (coerce/encode-properties object-map :object-type :object-identifier))))


(defn object-properties-values [device-id object-identifier]
  (let [refs (com.serotonin.bacnet4j.util.PropertyReferences.)]
    (.add refs object-identifier com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier/all)
    (.readProperties local-device (rd device-id) refs)))


(defn get-values
  "Return a map of every properties and their associated values."
  [device-id object-identifier]
  (coerce/bacnet->clojure (object-properties-values device-id object-identifier)))

(defn set-properties
  "Set all the properties present in the map."
  [device-id properties-map]
  (let [remote-device (rd device-id)
        encoded-properties (coerce/encode-properties properties-map)
        object-identifier (:object-identifier encoded-properties)]
    (for [prop (dissoc encoded-properties :object-identifier :object-type)]
      (let [prop-id (coerce/make-property-identifier (key prop))]
        (.setProperty local-device remote-device object-identifier prop-id (val prop))))))
