(ns bacure.core
  (:require [bacure.network :as network]
            [bacure.coerce :as coerce]
            [bacure.local-save :as save]))

(import '(com.serotonin.bacnet4j 
          LocalDevice 
          RemoteDevice
          obj.BACnetObject
          service.confirmed.CreateObjectRequest
          service.confirmed.DeleteObjectRequest
          service.unconfirmed.WhoIsRequest
          type.enumerated.PropertyIdentifier
          type.primitive.UnsignedInteger))

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


(defn remote-devices
  "Return the list of the current remote devices. These devices must
  be in the local table. To scan a network, use `discover-network'."
  []
  (for [rd (seq (.getRemoteDevices @local-device))]
    (.getInstanceNumber rd)))

(defn rd
  "Get the remote device by its device-id"
  [device-id]
  (.getRemoteDevice @local-device device-id))

(defn- extended-information
  "Get the remote device extended information (name, segmentation,
  property multiple, etc..) if we haven't already."[device-id]
  (let [device (rd device-id)]
    (when-not (.getName device)
      (try (-> @local-device
               (.getExtendedDeviceInformation device))
           (catch Exception e
             (.setSegmentationSupported device (coerce/c-segmentation :unknown))
             (.setServicesSupported device (coerce/c-services-supported {:read-property true})))))))
;; if there's an error while getting the extended device information, just assume that
;; there is almost no services supported. (Patch necessary until this function is implemented in clojure)


(defn all-extended-information
  "Make sure that we have the extended information of every known
   remote devices.

   Can be used some time the network discovery mechanism, as some
   devices might take a while to answer the WhoIs." []
   (doseq [device (remote-devices)]
     (extended-information device)))

(defn- find-remote-devices
  "We find remote devices by sending a 'WhoIs' broadcast. Every device
  that responds is added to the remote-devices field in the
  local-device. WARNING: This won't ask the device if it supports
  read-property-multiple. Thus, any property read based solely on this
  remote device discovery might fail. The use of `discover-network' is
  highly recommended, even if it might take a little longer to
  execute." [&[{:keys [min-range max-range dest-port]
      :or {dest-port (:destination-port @local-device-configs)}}]]
  (.sendBroadcast @local-device
                  dest-port (if (or min-range max-range)
                              (WhoIsRequest.
                               (UnsignedInteger. (or min-range 0))
                               (UnsignedInteger. (or max-range 4194304)))
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
   
(defn boot-up
  "Create a local-device, load its config file, initialize it, and
  find the remote devices." []
  (load-local-device-backup)
  (future (discover-network)) true)


(defn remote-devices-and-names
  "Return a list of vector pair with the device-id and its name.
   -->  ([1234 \"SimpleServer\"])" []
  (for [d (remote-devices)]
    [d (.getName (rd d))]))

(defn find-bacnet-port
  "Scan ports to see if any BACnet device will respond. If the
  optionals port-min and port-max aren't given, default to all ports
  between 47801 and 47820.

  By default each port will wait 500 ms for an answer. Consequently,
  if you test 20 ports, it will take 10 seconds.

  Example use:
  (find-bacnet-port) ;;will take 10s to scan ports 47801 to 47820
  (find-bacnet-port :delay 100) ;; scan the same ports, but faster
  (find-bacnet-port :port-min 47850 :port-max 47900) ;;new port range

  Even if we could simply send a WhoIs on another port, some BACnet
  devices have bad behaviour and send data back to 'their' port,
  regardless from which port the WhoIs came. In other to maximize our
  chances of finding them, we reset the local device with a new port
  each time." [&{:keys [delay port-min port-max] :or
                 {delay 500 port-min 47801 port-max 47820}}]
  (let [configs (local-device-backup)
        results (->> (for [port (range port-min port-max)]
                       (do (reset-local-device {:port port :destination-port port})
                           (find-remote-devices)
                           (Thread/sleep delay)
                           (when-let [devices (seq (remote-devices))]
                             {:port port :devices devices})))
                     (into [])
                     (remove nil?))]
    (reset-local-device configs)
    (future (discover-network))
    results))


;; ================================================================
;; Remote objects related functions
;; ================================================================


(defn create-remote-object
  "Send a 'create object request' to the remote device."
  [device-id object-map]
  (.send @local-device (rd device-id)
         (CreateObjectRequest. (coerce/c-object-identifier (:object-identifier object-map))
                               (coerce/encode-properties object-map :object-type :object-identifier
                                                         :object-list))))

(defn delete-remote-object
  "Send a 'delete object' request to a remote device."
  [device-id object-identifier]
  (.send @local-device (rd device-id)
         (DeleteObjectRequest. (coerce/c-object-identifier object-identifier))))


(defn- retrieve-prop-fn-chooser
  "Return a function to retrieve properties values. How the data is
  fetched is determined by the remote-device's segmentation support.

  If segmentation is supported, retrieve every objects at once. If it
  isn't, retrieve them one at the time." [device-id]
  (let [segmentation (-> (rd device-id) bean :segmentationSupported coerce/bacnet->clojure)]
    (fn [object-identifiers prop-identifiers]
      (if (= :both segmentation) ;; if segmentation is supported, merge everything together
        (let [refs (com.serotonin.bacnet4j.util.PropertyReferences.)]
          (doseq [object-identifier object-identifiers]
            (let [oid (coerce/c-object-identifier object-identifier)]
              (doseq [prop-id prop-identifiers]
                (.add refs oid prop-id))))
          (coerce/bacnet->clojure (.readProperties @local-device (rd device-id) refs)))
        (apply concat ;;if no segmentation is supported, just do one object per request
         (for [object-identifier object-identifiers]
           (let [refs (com.serotonin.bacnet4j.util.PropertyReferences.)
                 oid (coerce/c-object-identifier object-identifier)]
             (doseq [prop-id prop-identifiers]
               (.add refs oid prop-id))
             (coerce/bacnet->clojure (.readProperties @local-device (rd device-id) refs)))))))))

(defn remote-object-properties-with-nil
  "Query a remote device and return the properties values
   Example: (object-properties-values 1234 [:analog-input 0] :all)
   -> {:notification-class 4194303, :event-enable .....}

   Both `object-identifiers' and `properties' accept either a single
   item or a collection.

   You probably want to use `remote-object-properties'."
  [device-id object-identifiers properties]
  (let [object-identifiers ((fn[x] (if ((comp coll? first) x) x [x])) object-identifiers)
        properties ((fn [x] (if (coll? x) x [x])) properties)
        prop-identifiers (map #(coerce/make-property-identifier %) properties)]
    ((retrieve-prop-fn-chooser device-id) object-identifiers prop-identifiers)))

(defn remote-object-properties
  "Query a remote device and return the properties values
   Example: (remote-object-properties 1234 [:analog-input 0] :all)
   -> {:notification-class 4194303, :event-enable .....}

   Both `object-identifiers' and `properties' accept either a single
   item or a collection.

   Discards any properties with a `nil' value (property not found in object)."
  [device-id object-identifiers properties]
  (->> (remote-object-properties-with-nil device-id object-identifiers properties)
       (map (fn [m] (remove #(nil? (val %)) m)))
       (mapv #(into {} %))))

(defn remote-objects
  "Return a collection of every objects in the remote device.
   -> [[:device 1234] [:analog-input 0]...]"
  [device-id]
  (-> (remote-object-properties device-id [:device device-id] :object-list)
      ((comp :object-list first))))

(defn remote-objects-all-properties
  "Return a list of maps of every objects and their properties."
  [device-id]
  (remote-object-properties device-id (remote-objects device-id) :all))

(defn set-remote-properties
  "Set all the given objects properties in the remote device."
  [device-id properties-map]
  (let [remote-device (rd device-id)
        encoded-properties (coerce/encode properties-map)]
    (doseq [prop (dissoc encoded-properties :object-type :object-identifier :object-list)]
      (.setProperty @local-device remote-device
                    (:object-identifier encoded-properties)
                    (coerce/make-property-identifier (key prop))
                    (val prop)))))

(defn get-device-id
  "Return the device-id from a device-map (bunch of properties).

  This can be used to search the device-id amongst all the properties
  returned by (remote-objects-all-properties <some-device-id>)."
  [device-map]
  (->> (map :object-identifier device-map)
       (filter (comp #{:device} first))
       ((comp second first))))

;; ================================================================
;; Filtering and querying functions
;; ================================================================

(defn- where*
  [criteria not-found-result]
    (fn [m]
      (every? (fn [[k v]]
                (let [tested-value (get m k :not-found)]
                  (cond
                   (= tested-value :not-found) not-found-result
                   (and (fn? v) tested-value) (v tested-value)
                   (number? tested-value) (== tested-value v)
                   (and (= (class v) java.util.regex.Pattern)
                        (string? tested-value)) (re-find v tested-value)
                   (= tested-value v) :pass))) criteria)))

(defn where
  "Will test with criteria map as a predicate. If the value of a
  key-val pair is a function, use it as a predicate. If the tested map
  value is not found, fail.

  For example:
  Criteria map:  {:a #(> % 10) :b \"foo\"}
  Tested-maps  {:a 20 :b \"foo\"}  success
               {:b \"foo\"}        fail
               {:a nil :b \"foo\"} fail"
  [criteria]
  (where* criteria false))


(defn where-or-not-found
  "Will test with criteria map as a predicate. If the value of a
  key-val pair is a function, use it as a predicate. If the tested map
  value is not found, pass.

  For example:
  Criteria map:  {:a #(> % 10) :b \"foo\"}
  Tested-maps  {:a 20 :b \"foo\"}  success
               {:b \"foo\"}        success
               {:a nil :b \"foo\"} fail"
  [criteria]
  (where* criteria :pass))

(defn- update-objects-maps
  "Return the objects-maps with the new property added."
  [device-id objects-maps property]
  (->> (remote-object-properties-with-nil
         device-id (map :object-identifier objects-maps) property)
       (concat objects-maps)
       (group-by :object-identifier)
       vals
       (map #(apply merge %))))

  
(defn find-objects
  "Return a list of objects-maps (properties) matching the criteria-map.

   Criteria-map example:
   {:present-value #(> % 10) :object-name #\"(?i)analog\" :model-name \"GNU\"}
   
   Each different* property is requested individually, which requires
   a higher traffic on the network. However, it also enables us to
   stop a query if one of the properties values isn't what we wished.
   Useful if we want to find devices with particular properties.

   If the criteria-map fails, any unfetched properties will just be
   dropped, saving some traffic on the network. Can quickly become
   advantageous if we query a large number of devices.

   * In case of multiple objects, if segmentation is supported, the
     same property is retrieved in a single request. For example, the
     `description' property for 3 different objects would be merged
     into a single request."
  ([device-id criteria-map] (find-objects device-id criteria-map (remote-objects device-id)))
  ([device-id criteria-map object-identifiers]
     (let [properties (keys criteria-map)
           init-map (map #(hash-map :object-identifier %) object-identifiers)
           update-and-filter (fn [m p]
                               (when-let [updated-object-map (update-objects-maps device-id m p)]
                                 (filter (where-or-not-found criteria-map) updated-object-map)))]
       (reduce update-and-filter
               init-map
               properties))))


(defn find-objects-everywhere
  "Same as `find-objects', but will search every known devices on the network.
   Group the result in a map where the device object identifier is the
   key. E.g. [:device 1234]

   Thus, to retrieve the result for a given device, simply do:
   (get <result> [:device 1234]).

   One could also get the list of devices in which a result matched with:
   (keys <result>).

   Criteria-map example:
   {:present-value #(> % 10) :object-name #\"(?i)analog\" :model-name \"GNU\"}"
  ([criteria-map]
     (into {}
           (for [device (remote-devices)]
             (-> (find-objects device criteria-map)
                 ((fn [x] (when (seq x)
                            [[:device device] x])))))))
  ([criteria-map object-identifiers]
     (into {}
           (for [device (remote-devices)]
             (-> (find-objects device criteria-map object-identifiers)
                 ((fn [x] (when (seq x)
                            [[:device device] x]))))))))