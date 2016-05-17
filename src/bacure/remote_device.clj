(ns bacure.remote-device
  (:require [bacure.coerce :as c]
            [bacure.coerce.obj :as obj]
            [bacure.local-device :as ld]
            [bacure.read-properties :as rp]))


(import '(com.serotonin.bacnet4j 
          RemoteDevice
          service.confirmed.CreateObjectRequest
          service.confirmed.DeleteObjectRequest
          service.confirmed.WritePropertyRequest
          service.confirmed.WritePropertyMultipleRequest
          service.unconfirmed.WhoIsRequest
          exception.BACnetTimeoutException))


(defn rd
  "Get the remote device by its device-id"
  ([device-id] (rd nil device-id))
  ([local-device-id device-id]
   (.getRemoteDevice (ld/local-device-object local-device-id) device-id)))

(defn networking-info
  "Return a map with the networking info of the remote device. (The
  network number, the IP address, the port...)"
  ([device-id] (networking-info nil device-id))
  ([local-device-id device-id]
   (let [rd-address (.getAddress (rd local-device-id device-id))
         octet-string (.getMacAddress rd-address)
         [_ ip port] (re-find #"(.*):([0-9]*)" (.getDescription octet-string))]
     {:network-number (c/bacnet->clojure (.getNetworkNumber rd-address))
      :ip-address ip
      :port port
      :bacnet-mac-address (str (.getMacAddress rd-address))})))

(defn services-supported
  "Return a map of the services supported by the remote device."
  ([device-id] (services-supported nil device-id))
  ([local-device-id device-id]
   (-> (.getServicesSupported (rd local-device-id device-id))
       c/bacnet->clojure)))

(defn segmentation-supported
  "Return the type of segmentatin supported."
  ([device-id] (segmentation-supported nil device-id))
  ([local-device-id device-id]
   (-> (.getSegmentationSupported (rd local-device-id device-id))
       c/bacnet->clojure)))

(defn cached-extended-information
  "Return the cached remote device extended information. Nil if we have nothing." 
  ([device-id] (cached-extended-information nil device-id))
  ([local-device-id device-id]
   (when-let [device (rd local-device-id device-id)]
     ;; we got the 'extended info' when we have the services supported.
     (when (.getName device)
       {:protocol-services-supported (c/bacnet->clojure (.getServicesSupported device))
        :object-name (c/bacnet->clojure (.getName device))
        :protocol-version (c/bacnet->clojure (.getProtocolVersion device))
        :protocol-revision (c/bacnet->clojure (.getProtocolRevision device))}))))

(defn retrieve-extended-information!
  "Retrieve the remote device extended information (name, segmentation,
  property multiple, etc..) and update it locally.
  
  Return the cached extended information."
  ([device-id] (retrieve-extended-information! nil device-id))
  ([local-device-id device-id]
   (let [dev (rd local-device-id device-id)]
     ;; first step is to see if the device support read-property-multiple to enable faster read
     (when-let [services ;; don't do anything else if we can't get the protocol supported
                (-> (rp/read-individually local-device-id device-id [[[:device device-id] 
                                                                      :protocol-services-supported]])
                    (first)
                    (:protocol-services-supported))]
       (.setServicesSupported dev (c/clojure->bacnet :services-supported services))
       ;; then we can query for more info
       (let [result (first (rp/read-properties local-device-id device-id 
                                               [ [[:device device-id] :object-name 
                                                  :protocol-version :protocol-revision]]))]
         (.setName dev (:object-name result))
         (.setProtocolVersion dev (c/clojure->bacnet :unsigned-integer (:protocol-version result)))
         (.setProtocolRevision dev (c/clojure->bacnet :unsigned-integer (:protocol-revision result))))
       (cached-extended-information local-device-id device-id)))))

(defn extended-information
  "Return the device extended information that we have cached locally,
  or request it directly to the remote device."
  ([device-id] (extended-information nil device-id))
  ([local-device-id device-id]
   (or (cached-extended-information local-device-id device-id)
       (retrieve-extended-information! local-device-id device-id))))

(defn remote-devices
  "Return the list of the current remote devices. These devices must
  be in the local table. To scan a network, use `discover-network'."
  ([] (remote-devices nil))
  ([local-device-id]
   (if-let [ldo (ld/local-device-object local-device-id)] 
     (let [ld-id (ld/get-local-device-id ldo)]
       (->> (for [rd (seq (.getRemoteDevices ldo))]
              (.getInstanceNumber rd))
            (remove nil?)
            (into #{})))
     (throw (Exception. "Missing local device"))))) ;; into a set to force unique IDs

(defn remote-devices-and-names
  "Return a list of vector pair with the device-id and its name.
   -->  ([1234 \"SimpleServer\"])" 
  ([] (remote-devices-and-names nil))
  ([local-device-id]
   (for [d (remote-devices)]
     [d (.getName (rd d))])))

(defn all-extended-information
  "Make sure we have the extended information of every known
   remote devices.

   Can be used some time after the network discovery mechanism, as
   some devices might take a while to answer the WhoIs.

   Remote devices are queried in parallel." 
  ([] (all-extended-information nil))
  ([local-device-id]
   (doall
    (pmap #(try (extended-information local-device-id %)
                (catch Exception e))
          (remote-devices local-device-id)))))


(defn find-remote-devices
  "We find remote devices by sending a 'WhoIs' broadcast. Every device
  that responds is added to the remote-devices field in the
  local-device. WARNING: This won't ask the device if it supports
  read-property-multiple. Thus, any property read based solely on this
  remote device discovery might fail. The use of `discover-network' is
  highly recommended, even if it might take a little longer to
  execute."
  ([] (find-remote-devices {}))
  ([{:keys [min-range max-range] :as args}] (find-remote-devices nil args))
  ([local-device-id {:keys [min-range max-range]}]
   (.sendGlobalBroadcast (ld/local-device-object local-device-id)
                         (if (or min-range max-range)
                           (WhoIsRequest.
                            (c/clojure->bacnet :unsigned-integer (or min-range 0))
                            (c/clojure->bacnet :unsigned-integer (or max-range 4194304)))
                           (WhoIsRequest.)))))

(defn find-remote-device
  "Send a WhoIs for a single device-id, effectively finding a single
  device. Some devices seem to ignore a general WhoIs broadcast, but
  will answer a WhoIs request specifically for their ID."
  ([id] (find-remote-device nil id))
  ([local-device-id remote-device-id]
   (find-remote-devices local-device-id 
                        {:min-range remote-device-id :max-range remote-device-id})))


(defn- find-remote-devices-and-extended-information
  "Sends a WhoIs. For every device discovered,
  get its extended information. Return the remote devices as a list."
  ([] (find-remote-devices-and-extended-information {}))

  ([{:keys [min-range max-range dest-port] :as args}]
   (find-remote-devices-and-extended-information nil args))

  ([local-device-id {:keys [min-range max-range dest-port] :as args}]
   (find-remote-devices local-device-id args)
   (Thread/sleep 500) ;wait a little to insure we get the responses
   (all-extended-information local-device-id)
   (remote-devices local-device-id)))



(defn discover-network
  "Find remote devices and their extended info. By default, will try
   up to 5 time if not a single device answer. Return the list of
   remote-devices.

   Should be called in a future call to avoid `hanging' the program
   while waiting for the remote devices to answer."
  ([] (discover-network nil))
  ([local-device-id] (discover-network local-device-id 5))
  ([local-device-id tries]
     (dorun
      (->> (repeatedly tries #(find-remote-devices-and-extended-information local-device-id {}))
           (take-while empty?)))
     (remote-devices local-device-id)))







(defn create-remote-object!
  "Send a 'create object request' to the remote device. Must be given
  at least an :object-identifier OR an :object-type. If
  an :object-identifier isn't given, the numbering of the new object
  will be choosen by the remote device.
  
  Will block until we receive a response back, success or failure.
  If the request times out, an exception is thrown."
  ([device-id object-map] (create-remote-object! nil device-id object-map))
  ([local-device-id device-id object-map]
   (let [request (CreateObjectRequest. (if-let [o-id (:object-identifier object-map)]
                                         (c/clojure->bacnet :object-identifier o-id)
                                         (c/clojure->bacnet :object-type (:object-type object-map)))
                                       (obj/encode-properties object-map :object-type :object-identifier
                                                              :object-list))]
     (rp/send-request-promise local-device-id device-id request))))

(defn delete-remote-object!
  "Send a 'delete object' request to a remote device.
  
   Will block until we receive a response back, success or failure.
  If the request times out, an exception is thrown."
  ([device-id object-identifier] (delete-remote-object! nil device-id object-identifier))
  ([local-device-id device-id object-identifier]
   (let [request (DeleteObjectRequest. (c/clojure->bacnet :object-identifier object-identifier))]
     (rp/send-request-promise local-device-id device-id request))))



(defn set-remote-property!
  "Set the given remote object property.
  
   Will block until we receive a response back, success or failure."
  ([device-id object-identifier property-identifier property-value]
   (set-remote-property! nil device-id object-identifier property-identifier property-value))
  ([local-device-id device-id object-identifier property-identifier property-value]
   (let [obj-type (first object-identifier)
         encoded-value (obj/encode-property-value obj-type property-identifier property-value)
         request (WritePropertyRequest. (c/clojure->bacnet :object-identifier object-identifier)
                                        (c/clojure->bacnet :property-identifier property-identifier)
                                        nil
                                        encoded-value
                                        nil)]
     (rp/send-request-promise local-device-id device-id request))))


(defn set-remote-properties!
  "Set the given remote object properties.
  
  Will block until we receive a response back, success or failure.
  
  'write-access-specificiations' is a map of the form:
  {[:analog-input 1] [[:present-value 10.0][:description \"short description\"]]}

  If the remote device doesn't support 'write-property-multiple',
  fallback to writing all properties individually."
  ([device-id write-access-specificiations]
   (set-remote-properties! nil device-id write-access-specificiations))
  ([local-device-id device-id write-access-specificiations]
   (if (-> (ld/local-device-object local-device-id)
           (.getRemoteDevice device-id)
           (.getServicesSupported)
           c/bacnet->clojure
           :write-property-multiple)
     
     ;; normal behavior
     (let [req (WritePropertyMultipleRequest.
                (c/clojure->bacnet :sequence-of
                                   (map #(c/clojure->bacnet :write-access-specification %)
                                        write-access-specificiations)))]
       (rp/send-request-promise local-device-id device-id req))
     ;; fallback to writing properties individually
     (let [set-object-props! 
           (fn [[obj-id props]]
             (for [[prop-id prop-value] props]
               (-> (set-remote-property! local-device-id device-id obj-id prop-id prop-value)
                   (assoc :object-identifier obj-id
                          :property-id prop-id
                          :property-value prop-value))))]
       (->> (mapcat set-object-props! write-access-specificiations)
            (remove :success)
            (#(if (seq %) {:error {:write-properties-errors (vec %)}} {:success true})))))))



;; ;; ================================================================
;; ;; Maintenance of the remote devices list
;; ;; ================================================================

(defn is-alive? 
  "Check if the remote device is still alive. This is the closest
  thing to a 'ping' in the BACnet world." 
  ([device-id] (is-alive? nil device-id))
  ([local-device-id device-id]
   (try ;; try to read the :system-status property. In case of timeout,
     ;; catch the exception and return nil.
     (rp/read-properties local-device-id 
                         device-id
                         [[[:device device-id] :system-status]])
     (catch Exception e nil))))

