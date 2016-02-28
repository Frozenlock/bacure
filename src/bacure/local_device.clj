(ns bacure.local-device
  (:require [bacure.network :as net]
            [bacure.coerce :as c]
            [bacure.coerce.obj :as c-obj]
            [bacure.coerce.type.primitive]
            [bacure.coerce.type.enumerated]
            [bacure.coerce.type.constructed]
            [bacure.local-save :as save]))

(import '(com.serotonin.bacnet4j 
          LocalDevice
          obj.BACnetObject
          npdu.ip.IpNetwork
          transport.DefaultTransport))


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


;; we store all the local devices with their device-id as the key.
(defonce local-devices (atom {}))

(defn list-local-devices []
  (keys @local-devices))

(defn get-local-device 
  "Return the local-device associated with the device-id. If device-id
  is nil, simply return the first found."
  [device-id]
  (if-not device-id
    (some-> @local-devices first val)
    (get @local-devices device-id)))

(defn local-device-object
  "Return the local-device bacnet4j object associated with the
  device-id. If device-id is nil, simply return the first found."
  [device-id]
  (:bacnet4j-local-device (get-local-device device-id)))


          
(defn default-transport [network]
  (DefaultTransport. network))

(defn get-configs
  "Return a map of the local-device configurations"[local-device-id]
  (when-let [ldo (local-device-object local-device-id)]
    (let [config-o (.getConfiguration ldo)
          properties (.getProperty config-o (c/clojure->bacnet :property-identifier :property-list))]
      (->> (for [p properties
                 :let [p-name (c/bacnet->clojure p)]]
             [p-name (c/bacnet->clojure (.getProperty config-o p))])
           (into {})))))

(defn get-device-id 
  "Given a local device object, return the device ID."
  [device-object]
  (-> (.getConfiguration device-object)
      (.getProperty  (c/clojure->bacnet :property-identifier :object-identifier))
      (.getInstanceNumber)))

(defn- update-config!
  "Will update the given local-device config."
  [local-device-id property-identifier value]
  (.writeProperty (.getConfiguration (local-device-object local-device-id))
                  (c/clojure->bacnet :property-identifier property-identifier)
                  (c-obj/encode-property-value :device property-identifier value)))

(defn update-configs!
  "Given a map of properties, will update the local-device. Return the
  device configs. Please note that many properties CANNOT be changed
  while the device is initialized (:object-identifier for example) and
  will simply be discarded." [local-device-id properties-smap]
  (let [valid-properties (->> (keys (dissoc (get-configs local-device-id) :object-list))
                              (cons :description)) ;; what are the keys we should expect
        filtered-properties (select-keys properties-smap valid-properties)]
    ;; filter out any properties we are not expecting. This allows the
    ;; user to give a map containing other values.
    (doseq [[property-identifier value] filtered-properties]
      (update-config! local-device-id property-identifier value))
    ;; get the newly updated configs
    (get-configs local-device-id)))


(def default-configs
  "Some default configurations for device creation."
  {:device-id 1338
   :port 47808
   :model-name "Bacure"
   :vendor-identifier 697
   :description (str "BACnet device running on the open source Bacure stack. "
                     "See https://github.com/Frozenlock/bacure for details.")
   :vendor-name "HVAC.IO"})

(declare terminate!)

(defn new-local-device!
  "Return a new configured BACnet local device . (A device is required
  to communicate over the BACnet network.). Use the function
  'initialize' and 'terminate' to bind and unbind the device to the
  BACnet port. If needed, the initial configurations are available in
  the atom 'local-device-configs.

  The optional config map can contain the following:
  :device-id <number>
  :broadcast-address <string>
  :port <number>
  :local-address <string> <----- You probably don't want to use it.
  :'other-configs' <string> OR <number> OR other

  The device ID is the device identifier on the network. It should be
  unique.

  The broadcast-address is the address on which we send 'WhoIs'. You
  should not have to provide anything for this, unless you have
  multiple interfaces or want to trick your machine into sending to a
  'fake' broadcast address.

  Port and destination port are the BACnet port, usually 47808.

  The local-address will default to \"0.0.0.0\", also known as the
  'anylocal'. (Default by the underlying BACnet4J library.) This is
  required on Linux, Solaris and some Windows machines in order to
  catch packet sent as a broadcast. You can manually change it, but
  unless you know exactly what you are doing, bad things will happen.

  The 'other-configs' is any configuration returned when using the
  function 'get-configs'. These configuation can be set when the
  device is created simply by providing them in the arguments. For
  example, to change the vendor name, simply add '{:vendor-name \"some
  vendor name\"}'."
  ([] (new-local-device! nil))
  ([configs-map]
   (let [configs (merge default-configs configs-map)
         device-id (or (:device-id configs) 
                       (last (:object-identifier configs)) 
                       (:device-id default-configs))
         broadcast-address (or (:broadcast-address configs)
                               (net/get-broadcast-address (or (:local-address configs) (net/get-any-ip))))
         local-address (or (:local-address configs) IpNetwork/DEFAULT_BIND_IP)
         port (or (:port configs) IpNetwork/DEFAULT_PORT)
         tp (->> (net/ip-network-builder (mapify broadcast-address port local-address))
                 (default-transport))
         ld (LocalDevice. device-id tp)]
     ;; add the new local-device (and its configuration) into the
     ;; local devices table.
     (when (get-local-device device-id)
       (terminate! device-id))
     (swap! local-devices assoc device-id {:bacnet4j-local-device ld
                                           :init-configs configs})
     (update-configs! device-id configs)
     device-id)))

;;;;;;

(defn i-am-broadcast!
  "Send an 'I am' broadcast on the network." 
  ([] (i-am-broadcast! nil))
  ([local-device-id]
   (let [ldo (local-device-object local-device-id)]
     (->> (.getIAm ldo)
          (.sendGlobalBroadcast ldo)))))


(defn initialize!
  "Initialize the local device. This will bind it to it's port (most
  likely 47808) and load any programs available for the local-device.
  The port will remain unavailable until the device is terminated.
  Once terminated, you should discard the device and create a new one
  if needed.

  For more information on the local-device 'programs' operations, see
  the bacure.local-save namespace.

  Return true if initializing (binding to port) is successful."
  ([] (initialize! nil))
  ([local-device-id]
   (let [ldo (local-device-object local-device-id)]
     ;; try to bind to the bacnet port   
     (if (.isInitialized ldo)
       (println "The local device is already initialized.")
       (let [port (or (:port (get-configs local-device-id)) 47808)
             port-bind (try (do (.initialize ldo) true)
                            (catch java.net.BindException e
                              (do (println (str "\n*Error*: The BACnet port ("port") is already bound to another "
                                                "software.\n\t Please close the other software and try again.\n"))
                                  (throw e))))]
         ;; once we have the port, load the local programs
         (try (save/load-program)  ;; Anything in the program will be executed.
              (catch Exception e (println (str "Uh oh... couldn't load the local device program:\n"
                                               (.getMessage e)))))
         ;; return true if we are bound to the port
         port-bind)))))

(defn terminate!
  "Terminate the local device, freeing any bound port in the process."
  ([] (terminate! nil))
  ([local-device-id]
   (try (.terminate (local-device-object local-device-id))
        (catch Exception e)))) ;if the device isn't initialized, it will throw an error

(defn terminate-all!
  "Terminate all local devices."
  []
  (doseq [ld-id (list-local-devices)]
    (terminate! ld-id)))

(defmacro with-temp-devices
  "Execute body with a temporary set of local-devices. Any existing
  devices will be terminated before the body executes and re-initiated
  after. Useful for tests."
  [& body]
  `(let [initiated-devices# (doall 
                             (for [id# (list-local-devices)
                                   :when (.isInitialized (local-device-object id#))] id#))]
     (terminate-all!)
     (let [result# (atom nil)] 
       (with-redefs [local-devices (atom {})]
         (try (reset! result# (do ~@body))
              (catch Exception e#
                (println (str "Error: " (.getMessage e#))))
              (finally (terminate-all!))))
       (doseq [id# initiated-devices#]
         (initialize! id#))
       @result#)))

(defn local-device-backup
  "Get the necessary information to create a local device backup." 
  ([] (local-device-backup nil))
  ([local-device-id]
   (merge (:init-configs (get-local-device local-device-id)) 
          (get-configs local-device-id))))



;; ;; eventually we should be able to add programs in the local device

(defn reset-local-device!
  "Terminate the local device and discard it. Replace it with a new
  local device, and apply the same configurations as the previous one.
  If a map with new configurations is provided, it will be merged with
  the old config.

  (reset-local-device {:device-id 1112})
  ---> reset the device and change the device id."
  ([] (reset-local-device! nil nil))
  ([config-or-id] (if (map? config-or-id)
                    (reset-local-device! nil config-or-id)
                    (reset-local-device! config-or-id nil)))
  ([local-device-id new-config]
   (if-not (get-local-device local-device-id)
     ;; if we don't have a local device available, create one.
     (do (new-local-device! (merge {:device-id local-device-id} new-config))
         (initialize! local-device-id))
     ;; when we already have a local device, backup its configs, merge
     ;; them with the newly provided one and restart the device.
     (let [backup (local-device-backup local-device-id)]
       (terminate! local-device-id)
       (new-local-device! (merge backup new-config))
       (initialize! local-device-id)))))

(defn clear-all!
  "Destroy all traces of a local-device."[]
  (terminate-all!)
  (reset! local-devices {}))


(defn save-local-device-backup!
  "Save the device backup on a local file and return the config map."[]
  (save/save-configs (local-device-backup)))
;; eventually it would be nice to implement the BACnet backup procedure.


(defn load-local-device-backup!
  "Load the local-device backup file and reset it with this new
  configuration." 
  ([local-device-id] (load-local-device-backup! local-device-id nil))
  ([local-device-id new-configs]
   (reset-local-device! (merge (save/get-configs) new-configs))))
