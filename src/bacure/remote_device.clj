(ns bacure.remote-device
  (:require [bacure.coerce :as c]
            [bacure.coerce.obj :as obj]
            [bacure.local-device :as ld]
            [bacure.read-properties :as rp]
            [bacure.services :as services]
            [bacure.events :as events]
            [bacure.util :as util :refer [defnd]])
  (:import (com.serotonin.bacnet4j RemoteDevice
                                   event.DeviceEventAdapter
                                   service.confirmed.CreateObjectRequest
                                   service.confirmed.DeleteObjectRequest
                                   service.confirmed.WritePropertyRequest
                                   service.confirmed.WritePropertyMultipleRequest
                                   exception.BACnetTimeoutException)))

(defnd rd
  "Get the remote device object by its device-id"
  [local-device-id device-id]
  (some-> (events/cached-remote-devices local-device-id)
          (get device-id)))

(defnd networking-info
  "Return a map with the networking info of the remote device. (The
  network number, the IP address, the port...)"
  [local-device-id device-id]
  (let [rd-address   (.getAddress (rd local-device-id device-id))
        octet-string (.getMacAddress rd-address)
        [_ ip port]  (re-find #"(.*):([0-9]*)" (.getDescription octet-string))]
    {:network-number     (c/bacnet->clojure (.getNetworkNumber rd-address))
     :ip-address         ip
     :port               port
     :bacnet-mac-address (str (.getMacAddress rd-address))}))

(defnd services-supported
  "Return a map of the services supported by the remote device."
  [local-device-id device-id]
  (-> (.getServicesSupported (rd local-device-id device-id))
      c/bacnet->clojure))

(defnd segmentation-supported
  "Return the type of segmentatin supported."
  [local-device-id device-id]
  (-> (.getSegmentationSupported (rd local-device-id device-id))
      c/bacnet->clojure))


(def ^:private extended-information-properties
  [:protocol-services-supported
   :object-name
   :protocol-version
   :protocol-revision])

(defn- get-device-property [device-object property-identifier]
  (-> (.getDeviceProperty device-object (c/clojure->bacnet :property-identifier property-identifier))
      (c/bacnet->clojure)))

(defn- set-device-property! [device-object property-identifier value]
  (.setDeviceProperty device-object
                      (c/clojure->bacnet :property-identifier property-identifier)
                      (obj/encode-property-value :device property-identifier value)))

(defnd cached-extended-information
  "Return the cached remote device extended information. Nil if we have nothing."
  [local-device-id device-id]
  (when-let [device (rd local-device-id device-id)]
    ;; we got the 'extended info' when we have the services supported.
    (some->> (for [p-id extended-information-properties
                   :let [value (get-device-property device p-id)]
                   :when value]
               [p-id value])
             (seq)
             (into {}))))

(defnd retrieve-extended-information!
  "Retrieve the remote device extended information (name, segmentation,
  property multiple, etc..) and update it locally.

  Return the cached extended information."
  [local-device-id device-id]
  (when-let [dev (rd local-device-id device-id)]
    ;; first step is to see if the device support read-property-multiple to enable faster read
    (let [services ;; don't do anything else if we can't get the protocol supported
          (-> (rp/read-individually local-device-id device-id [[[:device device-id]
                                                                :protocol-services-supported]])
              (first)
              (:protocol-services-supported))]
      (when-not (:error services)
        (set-device-property! dev :protocol-services-supported services)
        ;; then we can query for more info
        (let [remaining-properties (remove #{:protocol-services-supported} extended-information-properties)
              result               (first (rp/read-properties local-device-id device-id
                                                              [[[:device device-id] :object-name
                                                                :protocol-version :protocol-revision]]))]
          (doseq [[k v] result]
            (when-not (:error v)
              (set-device-property! dev k v))))
        (cached-extended-information local-device-id device-id)))))

(defnd extended-information
  "Return the device extended information that we have cached locally,
  or request it directly to the remote device."
  [local-device-id device-id]
  (or (cached-extended-information local-device-id device-id)
      (retrieve-extended-information! local-device-id device-id)))

(defn IAm-received-auto-fetch-extended-information
  "Listen to IAm and try to fetch extended-information."
  [local-device-id]
  (proxy [DeviceEventAdapter] []
    (iAmReceived [remote-device]
      (extended-information local-device-id (.getInstanceNumber remote-device)))))

(defnd remote-devices
  "Return the list of the current remote devices. These devices must
  be in the local table. To scan a network, use `discover-network'."
  [local-device-id]
  (-> (events/cached-remote-devices local-device-id)
      (keys)
      (set)))

(defnd remote-devices-and-names
  "Return a list of vector pair with the device-id and its name.
   -->  ([1234 \"SimpleServer\"])"
  [local-device-id]
  (for [d (remote-devices)]
    [d (.getName (rd d))]))

(defnd all-extended-information
  "Make sure we have the extended information of every known
   remote devices.

   Can be used some time after the network discovery mechanism, as
   some devices might take a while to answer the WhoIs.

   Remote devices are queried in parallel."
  [local-device-id]
  (doall
   (pmap #(try (extended-information local-device-id %)
               (catch Exception e))
         (remote-devices local-device-id))))

(defn- remote-object-matches?
  [[object-identifier remote-object] object-identifier-or-name]
  (or (= object-identifier object-identifier-or-name)
      (= (:object-name remote-object) object-identifier-or-name)))

(defn- remote-device-has-object?
  [cached-remote-device-object object-identifier-or-name]
  (some true? (map #(remote-object-matches? % object-identifier-or-name)
                   cached-remote-device-object)))

(defnd get-remote-devices-having-object
  "Query our cached remote-objects to see which remote-devices have the
  specified object (if any). Use `find-remote-devices-having-object`
  to update the cache."
  [local-device-id object-identifier-or-name]
  (->> (events/cached-remote-objects local-device-id)
       (filter #(remote-device-has-object? (second %) object-identifier-or-name))
       keys
       (into #{})))

(defnd find-remote-devices-having-object
  "Do a Who-Has and return the remote-device-ids of any remote devices that
  respond. The Who-Has updates a cache that can be accessed at
  bacure.events/cached-remote-objects, and that is the same cache we query
  here."
  ([local-device-id object-identifier-or-name]
   (find-remote-devices-having-object local-device-id object-identifier-or-name nil))

  ([local-device-id object-identifier-or-name {:keys [min-range max-range wait-seconds]
                                               :or   {min-range 0 max-range 4194303 wait-seconds 1}
                                               :as   args}]
   (services/send-who-has local-device-id object-identifier-or-name args)
   (util/configurable-wait args)
   (get-remote-devices-having-object local-device-id object-identifier-or-name)))

(defnd find-remote-devices
  "We find remote devices by sending a 'WhoIs' broadcast. Every device
  that responds is added to the remote-devices field in the
  local-device. WARNING: This won't ask the device if it supports
  read-property-multiple. Thus, any property read based solely on this
  remote device discovery might fail. The use of `discover-network' is
  highly recommended, even if it might take a little longer to
  execute."
  ([local-device-id] (find-remote-devices local-device-id nil))
  ([local-device-id {:keys [min-range max-range wait-seconds]
                     :or   {min-range 0 max-range 4194303 wait-seconds 1}
                     :as   args}]
   (services/send-who-is local-device-id args)
   (util/configurable-wait args)
   (events/cached-remote-devices local-device-id)))

(defnd find-remote-device
  "Send a WhoIs for a single device-id, effectively finding a single
  device. Some devices seem to ignore a general WhoIs broadcast, but
  will answer a WhoIs request specifically for their ID."
  ([local-device-id remote-device-id]
   (find-remote-device local-device-id remote-device-id nil))

  ([local-device-id remote-device-id {:keys [wait-seconds]
                                      :or   {wait-seconds 1}
                                      :as   args}]
   (find-remote-devices local-device-id
                        (merge args
                               {:min-range remote-device-id
                                :max-range remote-device-id}))))


(defn- find-remote-devices-and-extended-information
  "Sends a WhoIs. For every device discovered,
  get its extended information. Return the remote devices as a list."
  ([] (find-remote-devices-and-extended-information {}))

  ([{:keys [min-range max-range dest-port] :as args}]
   (find-remote-devices-and-extended-information nil args))

  ([local-device-id {:keys [min-range max-range dest-port] :as args}]
   (find-remote-devices local-device-id args)
   (all-extended-information local-device-id)
   (remote-devices local-device-id)))


;; Warning : using `defnd` with `discover-network` would be a breaking
;; change. (Currently the single arity is to specify the
;; local-device-id, but it would be changed to 'tries' with `defnd`)
(defn discover-network
  "Find remote devices and their extended info. By default, will try
   up to 5 time if not a single device answer. Return the list of
   remote-devices.

   Should be called in a future call to avoid `hanging' the program
   while waiting for the remote devices to answer."
  ([] (discover-network nil))
  ([local-device-id] (discover-network local-device-id 5))
  ([local-device-id tries]
   (loop [remaining-tries tries]
     (when (> remaining-tries 0)
       (let [ids (find-remote-devices-and-extended-information local-device-id {})]
         (if (not-empty ids)
           ids
           (recur (dec remaining-tries))))))))


(defnd create-remote-object!
  "Send a 'create object request' to the remote device. Must be given
  at least an :object-identifier OR an :object-type. If
  an :object-identifier isn't given, the numbering of the new object
  will be choosen by the remote device.

  Will block until we receive a response back, success or failure.
  If the request times out, an exception is thrown."
  [local-device-id device-id object-map]
  (let [request (CreateObjectRequest. (if-let [o-id (:object-identifier object-map)]
                                        (c/clojure->bacnet :object-identifier o-id)
                                        (c/clojure->bacnet :object-type (:object-type object-map)))
                                      (obj/encode-properties object-map :object-type :object-identifier
                                                             :object-list))]
    (services/send-request-promise local-device-id device-id request)))

(defnd delete-remote-object!
  "Send a 'delete object' request to a remote device.

   Will block until we receive a response back, success or failure.
  If the request times out, an exception is thrown."
  [local-device-id device-id object-identifier]
  (let [request (DeleteObjectRequest. (c/clojure->bacnet :object-identifier object-identifier))]
    (services/send-request-promise local-device-id device-id request)))


(defn advanced-property
  "Take a property and wrap it inside a map with the priority and
  property-array-index."
  [property-value priority property-array-index]
  (if-not (and (map? property-value) (contains? property-value :value))
    {:value property-value
     :priority priority
     :property-array-index property-array-index}
    property-value))


(defnd set-remote-property!
  "Set the given remote object property.

   Will block until we receive a response back, success or failure.

  Property-value can be the value directly OR a map resulting from
  `advanced-property'"
  ([local-device-id device-id object-identifier property-identifier property-value]
   (let [obj-type       (first object-identifier)
         adv-props      (advanced-property property-value nil nil)
         priority       (:priority adv-props)
         prop-array-idx (:property-array-index adv-props)
         value          (let [value (:value adv-props)]
                          (if (nil? value)
                            (obj/force-type nil :null)
                            value))
         encoded-value  (obj/encode-property-value obj-type property-identifier value)
         request        (WritePropertyRequest. (c/clojure->bacnet :object-identifier object-identifier)
                                               (c/clojure->bacnet :property-identifier property-identifier)
                                               (when prop-array-idx
                                                 (c/clojure->bacnet :unsigned-integer prop-array-idx))
                                               encoded-value
                                               (when priority
                                                 (c/clojure->bacnet :unsigned-integer priority)))]
     (services/send-request-promise local-device-id device-id request))))

(defn- send-write-property-multiple-request
  [local-device-id device-id bacnet-write-access-specifications]
  (->> bacnet-write-access-specifications
       (c/clojure->bacnet :sequence-of)
       WritePropertyMultipleRequest.
       (services/send-request-promise local-device-id device-id)))

(defn- write-property-multiple
  [local-device-id device-id write-access-specifications]
  (->> write-access-specifications
       (map #(c/clojure->bacnet :write-access-specification %))
       (send-write-property-multiple-request local-device-id device-id)))

(defn write-single-multiple-properties
  [local-device-id device-id write-access-specifications]
  (let [set-object-props!
        (fn [[obj-id props]]
          (for [[prop-id prop-value] props]
            (-> (set-remote-property! local-device-id device-id obj-id prop-id prop-value)
                (assoc :object-identifier obj-id
                       :property-id prop-id
                       :property-value prop-value))))]
    (->> (mapcat set-object-props! write-access-specifications)
         (remove :success)
         (#(if (seq %) {:error {:write-properties-errors (vec %)}} {:success true})))))

(defnd set-remote-properties!
  "Set the given remote object properties.

  Will block until we receive a response back, success or failure.

  'write-access-specifications' is a map of the form:
  {[:analog-input 1] [[:present-value 10.0][:description \"short description\"]]}

  If the remote device doesn't support 'write-property-multiple',
  fallback to writing all properties individually."
  [local-device-id device-id write-access-specifications]
  (if (-> (services-supported device-id) :write-property-multiple)
    ;; normal behavior
    (write-property-multiple local-device-id device-id write-access-specifications)
    ;; fallback to writing properties individually
    (write-single-multiple-properties local-device-id device-id write-access-specifications)))

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



;; ================================================================
;; Test helpers
;; ================================================================


(defn local-registered-test-devices!
  "Boot up local devices and return their IDs.
  The devices are registered as foreign devices to each other."
  [qty]
  (let [port 47555 ; Unlikely to mess with existing BACnet network.
        id->ip (into {} (map (juxt :id :ip-address) (ld/local-test-devices! qty port)))
        know-each-other? (fn [ids]
                           (every? #(= (set (remove #{%} ids))
                                       (remote-devices %))
                                   ids))]
    ;; Make devices aware of each other
    (doseq [[ld-id _] id->ip] ; current local device
      (doseq [[_ rd-ip] (dissoc id->ip ld-id)] ; all the other devices
        (ld/register-as-foreign-device ld-id rd-ip port 60)))
    (doseq [id (keys id->ip)]
      (ld/i-am-broadcast! id))
    (util/wait-while #(not (know-each-other? (keys id->ip))) 500)
    (keys id->ip)))
