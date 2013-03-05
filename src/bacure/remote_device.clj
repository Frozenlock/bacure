(ns bacure.remote-device
  (:require [bacure.coerce :as coerce]
            [bacure.local-device :as ld]))


(import '(com.serotonin.bacnet4j 
          RemoteDevice
          service.confirmed.CreateObjectRequest
          service.confirmed.DeleteObjectRequest
          service.unconfirmed.WhoIsRequest
          ))


(defn rd
  "Get the remote device by its device-id"
  [device-id]
  (.getRemoteDevice @ld/local-device device-id))

(defn services-supported
  "Return a map of the services supported by the remote device."
  [device-id]
  (-> (.getServicesSupported (rd device-id))
      coerce/bacnet->clojure))
;; contrary to the bacnet4j library, we won't throw an error when
;; dealing with devices not yet supporting the post 1995 services.

(defn segmentation-supported
  "Return the type of segmentatin supported."
  [device-id]
  (-> (.getSegmentationSupported (rd device-id))
      coerce/bacnet->clojure))

(defn extended-information?
  "True if we already have the device extended information, nil
  otherwise." [device-id]
  (.getName (rd device-id)))

(defn extended-information
  "Get the remote device extended information (name, segmentation,
  property multiple, etc..) if we haven't already."
  [device-id]
  (let [device (rd device-id)]
    (when-not (extended-information? device-id)
      (try (-> @ld/local-device
               (.getExtendedDeviceInformation device))
           (catch Exception e
             ; if there's an error while getting the extended device
             ; information, just assume that there is almost no
             ; services supported. (Patch necessary until this
             ; function is implemented in clojure)
             (.setSegmentationSupported device (coerce/c-segmentation :unknown))
             (.setServicesSupported device (coerce/c-services-supported {:read-property true}))))
      ;; and now update the services supported to be compatible with pre 1995 BACnet
      (->> (services-supported device-id)
           coerce/c-services-supported
           (.setServicesSupported device)))))

(defn remote-devices
  "Return the list of the current remote devices. These devices must
  be in the local table. To scan a network, use `discover-network'."
  []
  (try
    (for [rd (seq (.getRemoteDevices @ld/local-device))]
      (.getInstanceNumber rd))
    (catch Exception e))) ;; if there isn't any remote device, throws
                          ;; a null exception

(defn remote-devices-and-names
  "Return a list of vector pair with the device-id and its name.
   -->  ([1234 \"SimpleServer\"])" []
  (for [d (remote-devices)]
    [d (.getName (rd d))]))

(defn all-extended-information
  "Make sure that we have the extended information of every known
   remote devices.

   Can be used some time after the network discovery mechanism, as
   some devices might take a while to answer the WhoIs.

   Remote devices are queried in parallel." []
   (doall (pmap extended-information (remote-devices))))


(defn find-remote-devices
  "We find remote devices by sending a 'WhoIs' broadcast. Every device
  that responds is added to the remote-devices field in the
  local-device. WARNING: This won't ask the device if it supports
  read-property-multiple. Thus, any property read based solely on this
  remote device discovery might fail. The use of `discover-network' is
  highly recommended, even if it might take a little longer to
  execute." [&[{:keys [min-range max-range dest-port]
      :or {dest-port (:destination-port @ld/local-device-configs)}}]]
  (.sendBroadcast @ld/local-device
                  dest-port (if (or min-range max-range)
                              (WhoIsRequest.
                               (coerce/c-unsigned (or min-range 0))
                               (coerce/c-unsigned (or max-range 4194304)))
                              (WhoIsRequest.))))


(defn- find-remote-devices-and-extended-information
  "Sends a WhoIs. For every device discovered,
  get its extended information. Return the remote devices as a list."
  [&[{:keys [min-range max-range dest-port] :as args}]]
  (find-remote-devices args)
  (Thread/sleep 500) ;wait a little to insure we get the responses
  (all-extended-information)
  (remote-devices))

(defn discover-network
  "Find remote devices and their extended info. By default, will try
   up to 5 time if not a single device answer. Return the list of
   remote-devices.

   Should be called in a future call to avoid `hanging' the program
   while waiting for the remote devices to answer."
  ([] (discover-network 5))
  ([tries]
     (dorun
      (->> (repeatedly tries find-remote-devices-and-extended-information)
           (take-while empty?)))
     (seq (remote-devices))))

(defn create-remote-object
  "Send a 'create object request' to the remote device."
  [device-id object-map]
  (.send @ld/local-device (rd device-id)
         (CreateObjectRequest. (coerce/c-object-identifier (:object-identifier object-map))
                               (coerce/encode-properties object-map :object-type :object-identifier
                                                         :object-list))))

(defn delete-remote-object
  "Send a 'delete object' request to a remote device."
  [device-id object-identifier]
  (.send @ld/local-device (rd device-id)
         (DeleteObjectRequest. (coerce/c-object-identifier object-identifier))))


(defn set-remote-properties
  "Set all the given objects properties in the remote device."
  [device-id properties-map]
  (let [remote-device (rd device-id)
        encoded-properties (coerce/encode properties-map)]
    (doseq [prop (dissoc encoded-properties :object-type :object-identifier :object-list)]
      (.setProperty @ld/local-device remote-device
                    (:object-identifier encoded-properties)
                    (coerce/make-property-identifier (key prop))
                    (val prop)))))
