(ns bacure.local-device
  (:require [bacure.network :as network]
            [bacure.coerce :as coerce]
            [bacure.local-save :as save]))

(import '(com.serotonin.bacnet4j 
          LocalDevice
          obj.BACnetObject))


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

(defn new-local-device
  "Return a new configured BACnet local device . (A device is required
   to communicate over the BACnet network.). To terminate it, use the
   java method `terminate'. If needed, the initial configurations are
   available in the atom 'local-device-configs.

   The optional config map can contain the following:
   :device-id <number>
   :broadcast-address <string>
   :port <number>
   :destination-port <number>
   :local-address <string> <----- You probably don't want to use it.
   :timeout <number>

   The device ID is the device identifier on the network. It should be
   unique.

   Port and destination port are the BACnet port, usually 47808.

   The broadcast-address is the address on which we send 'WhoIs'.

   The local-address will default to \"0.0.0.0\", also known as the
   'anylocal'. (Default by the underlying BACnet4J library.) This is
   required on Linux and Solaris machines in order to catch packet
   sent as a broadcast. You can manually change it, but unless you
   know exactly what you are doing, bad things will happen."
  ([] (new-local-device nil))
  ([configs-map]
     (let [{:keys [device-id broadcast-address port destination-port local-address timeout]
            :or {device-id 1338 ;;some default configs
                 broadcast-address (network/get-broadcast-address (network/get-any-ip))
                 port 47808
                 destination-port 47808
                 timeout 1000}}
           configs-map
           ld (LocalDevice. device-id broadcast-address local-address)]
       (.setPort ld port)
       (.setTimeout ld timeout) ;; increase the default to 20s
       (reset! local-device ld)
       (reset! local-device-configs (mapify device-id broadcast-address port
                                            destination-port local-address timeout))
       ld)))



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
  (let [object-id (coerce/c-object-identifier (:object-identifier object-map))]
    (try (.addObject @local-device
                     (BACnetObject. @local-device object-id))
         (catch Exception e (print (str "caught exception: " (.getMessage e)))))
    (.getObject @local-device object-id)))
  
(defn add-or-update-object
  "Update a local object properties. Create the object it if not
  already present. Will not try to modify any :object-type
  or :object-identifier." [object-map]
  (let [object-id (coerce/c-object-identifier (:object-identifier object-map))
        b-obj (or (.getObject @local-device object-id) (add-object object-map))]
    (doseq [prop (coerce/encode-properties object-map :object-identifier :object-type)]
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
  "Spit all important information about the local device into a map." []
  (merge @local-device-configs
         (when @local-device
           {:objects (local-objects)
            :port (.getPort @local-device)
            :retries (.getRetries @local-device)
            :seg-timeout (.getSegTimeout @local-device)
            :seg-window (.getSegWindow @local-device)
            :timeout (.getTimeout @local-device)})))
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
     (terminate)
     (new-local-device (merge (local-device-backup) new-config))
     (let [configs (local-device-backup)]
       (doto @local-device
         (.setRetries (:retries configs))
         (.setSegTimeout (:seg-timeout configs))
         (.setSegWindow (:seg-window configs))
         (.setTimeout (:timeout configs)))
       (initialize)
       (doseq [o (or (:objects configs) [])]
         (add-or-update-object o)))))

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
