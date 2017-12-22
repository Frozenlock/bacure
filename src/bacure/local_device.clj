(ns bacure.local-device
  (:require [bacure.network :as net]
            [bacure.coerce :as c]
            [bacure.coerce.obj :as c-obj]
            [bacure.coerce.type.primitive]
            [bacure.coerce.type.enumerated]
            [bacure.coerce.type.constructed]
            [bacure.local-save :as save]
            [bacure.serial-connection :as serial]
            [bacure.state :as state]
            [bacure.events :as events])
  (:import (com.serotonin.bacnet4j LocalDevice
                                   obj.BACnetObject
                                   npdu.ip.IpNetwork
                                   transport.DefaultTransport)
           (java.net InetSocketAddress)))

;; we store all the local devices with their device-id as the key.

(defn list-local-devices []
  (keys @state/local-devices))

(defn get-local-device
  "Return the local-device associated with the device-id. If device-id
  is nil, simply return the first found."
  [device-id]

  (state/get-local-device device-id))

(defn local-device-object
  "Return the local-device bacnet4j object associated with the
  device-id. If device-id is nil, simply return the first found."
  [device-id]
  (state/get-in-local-device device-id [:bacnet4j-local-device]))


(defn default-transport [network]
  (DefaultTransport. network))


(defn- get-all-device-properties [ldo]
  (-> (into {} (for [p (c-obj/properties-by-option :device :all)]
                 [p (-> (.get ldo (c/clojure->bacnet :property-identifier p))
                        (c/bacnet->clojure))]))
      ;; don't care about those properties for now
      (dissoc :tags :active-vt-sessions :active-cov-subscriptions :reliability-evaluation-inhibit :active-cov-multiple-subscriptions
              :align-intervals :device-address-binding :daylight-savings-status :event-detection-enable :restart-notification-recipients)))

(defn get-configs
  "Return a map of the local-device configurations"
  [local-device-id]
  (when-let [ldo (local-device-object local-device-id)]
    (get-all-device-properties ldo)))

(defn get-local-device-id
  "Given a local device object, return the device ID."
  [device-object]
  (->> (c/clojure->bacnet :property-identifier :object-identifier)
       (.get device-object)
       (.getInstanceNumber)))

(defn- update-config!
  "Will update the given local-device config."
  [local-device-id property-identifier value]
  (let [ldo (local-device-object local-device-id)]
    (.writePropertyInternal ldo
                            (c/clojure->bacnet :property-identifier property-identifier)
                            (c-obj/encode-property-value :device property-identifier value))))

(defn update-configs!
  "Given a map of properties, will update the local-device. Return the
  device configs. Please note that many properties CANNOT be changed
  while the device is initialized (:object-identifier for example) and
  will simply be discarded." [local-device-id properties-smap]
  (let [valid-properties (-> (keys (dissoc (get-configs local-device-id) :object-list))
                             (conj :description :object-name)) ;; what are the keys we should expect
        filtered-properties (select-keys properties-smap valid-properties)]
    ;; filter out any properties we are not expecting. This allows the
    ;; user to give a map containing other values.
    (doseq [[property-identifier value] filtered-properties]
      (update-config! local-device-id property-identifier value))
    ;; get the newly updated configs
    (get-configs local-device-id)))


(def default-configs
  "Some default configurations for device creation."
  {:network-type :ipv4
   :device-id 1338
   :port 47808
   :model-name "Bacure"
   :vendor-identifier 697
   :apdu-timeout 6000
   :number-of-apdu-retries 2
   :broadcast-address (net/get-broadcast-address (net/get-any-ip))
   :description (str "BACnet device running on the open source Bacure stack. "
                     "See https://github.com/Frozenlock/bacure for details.")
   :vendor-name "HVAC.IO"})

(defn- get-sane-configs-map
  [configs-map]
  {:pre [(#{:ipv4 :mstp} (:network-type configs-map))]}

  (assoc configs-map
         :device-id (or (:device-id configs-map)
                        (last (:object-identifier configs-map))
                        (:device-id default-configs))
         :broadcast-address (or (:broadcast-address configs-map)
                                (net/get-broadcast-address (or (:local-address configs-map) (net/get-any-ip))))
         :local-address (or (:local-address configs-map) IpNetwork/DEFAULT_BIND_IP)
         :port (or (:port configs-map) IpNetwork/DEFAULT_PORT)))

(declare terminate!)

(defn- get-transport
  [network configs-map]
  (let [tp (default-transport network)]
    (when-let [retries (:number-of-apdu-retries configs-map)]
      (.setRetries tp retries))
    (when-let [timeout (:apdu-timeout configs-map)]
      (.setTimeout tp timeout))
    (when-let [seg-timeout (:adpu-seg-timeout configs-map)]
      (.setSegTimeout tp seg-timeout))
    tp))

(defn new-local-device!
  "Return a new configured BACnet local device . (A device is required
  to communicate over the BACnet network.). Use the function
  'initialize' and 'terminate' to bind and unbind the device to the
  BACnet port. If needed, the initial configurations are associated
  with the keyword :init-configs inside the local-device map.'

  See README for more information about how to specify configs-map."
  ([] (new-local-device! nil))
  ([configs-map]
   (let [configs (->> configs-map
                      (merge default-configs)
                      get-sane-configs-map)
         {:keys [device-id broadcast-address port local-address com-port]} configs
         serial-connection (if (some? com-port)
                             (serial/get-opened-serial-connection! com-port configs))
         network (case (:network-type configs)
                   :ipv4 (net/ip-network-builder configs)
                   :mstp (net/create-mstp-network serial-connection configs))

         tp      (get-transport network configs)
         ld      (LocalDevice. device-id tp)]
     ;; add the new local-device (and its configuration) into the
     ;; local devices table.

     (events/add-listener! ld device-id)

     (when (get-local-device device-id)
       (terminate! device-id))

     (state/assoc-local-device! device-id
                                {:bacnet4j-local-device ld
                                 :serial-connection serial-connection
                                 :remote-devices #{}
                                 :remote-objects {}
                                 :cov-events {}
                                 :init-configs (merge configs
                                                      {:device-id device-id
                                                       :broadcast-address broadcast-address
                                                       :port port
                                                       :local-address local-address})})
     (update-configs! device-id configs)
     device-id)))

;;;;;;

(defn register-as-foreign-device
  "Register the local device as a foreign device in a device located
  on another network. Will block until the registration is completed
  or the request times out.

  The time-to-live is the time after which we should be removed from
  the foreign device table (if we don't re-register).

  Re-registration are handled automatically.

  Throws a BACnet exception if timeout, a NAK is received, the device
  is already registered or if the request couldn't be sent."
  ([target-ip-or-hostname target-port time-to-live]
   (register-as-foreign-device nil target-ip-or-hostname target-port time-to-live))
  
  ([local-device-id target-ip-or-hostname target-port time-to-live]
   (some-> (state/get-in-local-device local-device-id [:bacnet4j-local-device])
           (.getNetwork)
           (.registerAsForeignDevice
            (java.net.InetSocketAddress. target-ip-or-hostname target-port) time-to-live))))

(defn unregister-as-foreign-device
  "Unregister as a foreign device. Should be done automatically when
  terminating a local device."
  ([] (unregister-as-foreign-device nil))

  ([local-device-id]
   (some-> (state/get-in-local-device local-device-id [:bacnet4j-local-device])
           (.getNetwork)
           (.unregisterAsForeignDevice))))


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

(defn- close-serial-connection-for-device!
  "Closes any serial connection referred to by the given device, if it both exists
  and is open."
  [local-device-id]
  (some-> (get-local-device local-device-id)
          (get-in [:init-configs :com-port])
          serial/ensure-connection-closed!))

(defn terminate!
  "Terminate the local device, freeing any bound port in the process. Close its
  serial connection if it exists afterwards. (If we don't terminate the device
  first, and it's got an MS/TP node, it'll try to access a closed serial
  connection in its MS/TP node thread and it'll throw angrily.)"
  ([] (terminate! nil))
  ([local-device-id]
   (try
     (.terminate (local-device-object local-device-id))
     (catch Exception e)) ;if the device isn't initialized, it will throw an error
   (close-serial-connection-for-device! local-device-id)))

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
       (with-redefs [state/local-devices (atom {})]
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

(defn clear!
  "Destroy all traces of one local-device."
  [local-device-id]

  (terminate! local-device-id)
  (state/dissoc-local-device! local-device-id))

(defn clear-all!
  "Destroy all traces of all local-devices."
  []
  (terminate-all!)
  (state/clear-local-devices!))

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

(defn- set-communication-state!
  ([state] (set-communication-state! state nil 1))

  ([state minutes] (set-communication-state! state nil minutes))

  ([state local-device-id minutes]
   (-> (local-device-object local-device-id)
       (.setCommunicationControl (c/clojure->bacnet :enable-disable state) minutes))))

(def disable-communications! (partial set-communication-state! :disable))
(def enable-communications! (partial set-communication-state! :enable))
