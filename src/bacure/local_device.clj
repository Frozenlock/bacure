(ns bacure.local-device
  (:require [bacure.network :as net]
            [bacure.coerce :as coerce]
            [bacure.local-save :as save]))

(import '(com.serotonin.bacnet4j 
          LocalDevice
          obj.BACnetObject
          npdu.Network
          npdu.ip.IpNetwork
          transport.Transport))


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


;; ================


(def local-device (atom nil))
(def local-device-configs (atom {}))

;;;;;;


;; create new network (IP)
(def net (com.serotonin.bacnet4j.npdu.ip.IpNetwork. ))

(defn ip-network
  "Create a new ip network for a local-device."
  ([] (com.serotonin.bacnet4j.npdu.ip.IpNetwork.))
  ([broadcast-address]
     (com.serotonin.bacnet4j.npdu.ip.IpNetwork. broadcast-address))
  ([broadcast-address port]
     (com.serotonin.bacnet4j.npdu.ip.IpNetwork. broadcast-address port))
  ([broadcast-address port local-address]
     (com.serotonin.bacnet4j.npdu.ip.IpNetwork. broadcast-address port local-address))
  ([broadcast-address port local-address network-number]
     (com.serotonin.bacnet4j.npdu.ip.IpNetwork. broadcast-address port local-address network-number)))
          
(defn transport [network]
  (Transport. network))


(defn- get-java-config []
  (.getConfiguration @local-device))

(defn get-configs
  "Return a map of the local-device configurations"[]
  (coerce/bacnet->clojure (.getConfiguration @local-device)))

(defn- update-config
  "Will update the given local-device config."
  [property-identifier value]
  (.setProperty (get-java-config) 
                (coerce/c-property-identifier property-identifier)
                (coerce/encode-property :device property-identifier value)))

(defn update-configs
  "Given a map of properties, will update the local-device. Return the
  device configs." [properties-smap]
  (doall (map #(update-config (key %) (val %)) properties-smap))
  (get-configs))


(def defaults-config
  "Some default configurations for device creation."
  {:model-name "Bacure"
   :vendor-identifier 999
   :description "BACnet device running on the open source Bacure stack. See www.bacnethelp.com for details."
   :vendor-name "BACnetHelp.com"})


(defn new-local-device
  "Return a new configured BACnet local device . (A device is required
   to communicate over the BACnet network.). To terminate it, use the
   java method `terminate'. If needed, the initial configurations are
   available in the atom 'local-device-configs.

   The optional config map can contain the following:
   :device-id <number>
   :broadcast-address <string>
   :port <number>
   :local-address <string> <----- You probably don't want to use it.
   :timeout <number>
   :'other-configs' <string> OR <number> OR other

   The device ID is the device identifier on the network. It should be
   unique.

   Port and destination port are the BACnet port, usually 47808.

   The broadcast-address is the address on which we send 'WhoIs'.

   The 'other-configs' is any configuration returned when using the
   function 'get-configs'. These configuation can be set when the
   device is created simply by providing them in the arguments. For
   example, to change the vendor name, simply add ':vendor-name \"some
   vendor name\"'.

   The local-address will default to \"0.0.0.0\", also known as the
   'anylocal'. (Default by the underlying BACnet4J library.) This is
   required on Linux and Solaris machines in order to catch packet
   sent as a broadcast. You can manually change it, but unless you
   know exactly what you are doing, bad things will happen."
  ([] (new-local-device nil))
  ([configs-map]
     (let [{:keys [device-id broadcast-address port local-address timeout]
            :or {device-id 1338 ;;some default configs
                 port com.serotonin.bacnet4j.npdu.ip.IpNetwork/DEFAULT_PORT
                 timeout 1000}} configs-map]
       (let [broadcast-address (or broadcast-address
                                   (net/get-broadcast-address (or local-address (net/get-any-ip))))
             local-address (or local-address com.serotonin.bacnet4j.npdu.ip.IpNetwork/DEFAULT_BIND_IP)
             other-configs (dissoc configs-map :device-id :broadcast-address :port :local-address :timeout)  
             tp (->> (ip-network broadcast-address
                                 port
                                 local-address)
                     (transport))
             ld (LocalDevice. device-id tp)]
         (.setTimeout tp timeout) ;; increase the default to 20s
         (reset! local-device ld)
         (update-configs (merge defaults-config other-configs))
         (reset! local-device-configs (assoc @local-device-configs :provided-configs configs-map))
         ld))))

;;;;;;


(defn initialize
  "Initialize the local device. This will bind it to it's port (most
  likely 47808). The port will remain unavailable until the device is
  terminated. Once terminated, you should discard the device and
  create a new one if needed."[]
  (.initialize @local-device))

(defn terminate
  "Terminate the local device, freeing any bound port in the process."[]
  (try (.terminate @local-device)
       (catch Exception e))) ;if the device isn't initialized, it will throw an error


(defn local-objects
  "Return a list of local objects"[]
  (mapv coerce/bacnet->clojure
        (.getLocalObjects @local-device)))

(defn add-object
  "Add a local object and return it. You should probably just use `add-or-update-object'."
  [object-map]
  (let [object-id (coerce/c-object-identifier (:object-identifier object-map))
        bacnet-object (BACnetObject. @local-device object-id)]
    (doseq [prop (coerce/encode-properties object-map :object-identifier :object-type)]
      ;; print an error message letting the user know that the property might not have been implemented yet
      (try (.setProperty bacnet-object prop) (catch Exception e (println (str (.getMessage e) "\n")))))
    (try (.addObject @local-device
                     bacnet-object)
         (catch Exception e));; error if object already exists
    (.getObject @local-device object-id)))
  
(defn add-or-update-object
  "Update a local object properties. Create the object it if not
  already present. Will not try to modify any :object-type
  or :object-identifier." [object-map]
  (let [object-id (coerce/c-object-identifier (:object-identifier object-map))
        b-obj (or (.getObject @local-device object-id) (add-object object-map))]
    (doseq [prop (coerce/encode-properties object-map :object-identifier :object-type :priority-array)]
      (.setProperty b-obj prop))
    (coerce/bacnet->clojure b-obj)))


(defn remove-object
  "Remove a local object"
  [object-map]
  (.removeObject @local-device
                 (coerce/c-object-identifier (:object-identifier object-map))))

(defn remove-all-objects
  "Remove all local object"[]
  (doseq [o (local-objects)]
    (remove-object o)))

(defn local-device-backup
  "Spit all important information about the local device into a map.
   :configs are all the configs returned by 'get-configs'
   :objects is the list of local objects (and their properties)
   :provided-configs are the configuration given when the device was
   created using `new-local-device'." []
   (merge @local-device-configs
          (when @local-device
            {:objects (local-objects)
             :configs (dissoc (get-configs) :object-identifier :object-list)})))



;; eventually we should be able to add programs in the local device

(defn reset-local-device
  "Terminate the local device and discard it. Replace it with a new
  local device, and apply the same configurations as the previous one.
  If a map with new configurations is provided, it will be merged with
  the old config.

  (reset-local-device {:device-id 1112})
  ---> reset the device and change the device id."
  ([] (reset-local-device nil))
  ([new-config]
     (let [backup (local-device-backup)]
       (terminate)
       (new-local-device (merge (:provided-config backup) new-config))
       (initialize)
       (doseq [o (or (:objects backup) [])]
         (add-or-update-object o))
       (update-configs (:configs backup)))))

(defn clear-all!
  "Mostly a development function; Destroy all traces of a local-device."[]
  (terminate)
  (def local-device (atom nil))
  (def local-device-configs (atom {})))


(defn save-local-device-backup
  "Save the device backup on a local file and return the config map."[]
  (save/save-configs (local-device-backup)))
;; eventually it would be nice to implement the BACnet backup procedure.


(defn load-local-device-backup
  "Load the local-device backup file and reset it with this new
  configuration." []
  (reset-local-device (save/get-configs)))
