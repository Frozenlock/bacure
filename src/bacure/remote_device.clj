(ns bacure.remote-device
  (:require [bacure.coerce :as c]
            [bacure.coerce.obj :as obj]
            [bacure.events :as events]
            [bacure.local-device :as ld]
            [bacure.read-properties :as rp]
            [bacure.services :as services]
            [bacure.state :as state]
            [bacure.util :as util :refer [defnd]]
            [clojure.tools.logging :as log]
            [com.climate.claypoole :as claypoole])
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

(defn- device-extended-information
  "Return the extended information stored in the RemoteDevice object.
  Nil if we have nothing."
  [device]
  (some->> (for [p-id extended-information-properties
                 :let [value (get-device-property device p-id)]
                 :when value]
             [p-id value])
           (seq)
           (into {})))

(defn- complete-extended-information?
  "The extended information is complete when we know how to talk to
  the device (services supported) and what it is (object-name)."
  [info]
  (boolean (and (:protocol-services-supported info)
                (:object-name info))))

(defnd cached-extended-information
  "Return the cached remote device extended information. Nil if we have nothing.

  Might be partial (for example the services supported without the
  object-name) if the device stopped answering midway."
  [local-device-id device-id]
  (some-> (rd local-device-id device-id)
          (device-extended-information)))

(defn- fetch-extended-information!
  "Send the requests to the remote device and store the results in
  its RemoteDevice object.

  By default, only ask for what we don't already have. With
  'refresh?', ask for everything again.

  Return the extended information we have for the device (might be
  incomplete), or nil if we don't know its services."
  [local-device-id device-id dev refresh?]
  ;; first step is to see if the device support read-property-multiple to enable faster read
  (let [known-services (when-not refresh?
                         (get-device-property dev :protocol-services-supported))
        services       (or known-services
                           (-> (rp/read-individually local-device-id device-id [[[:device device-id]
                                                                                 :protocol-services-supported]])
                               (first)
                               (:protocol-services-supported)))]
    ;; don't do anything else if we can't get the protocol supported
    (when (and services (not (:error services)))
      (when-not known-services
        (set-device-property! dev :protocol-services-supported services))
      ;; then we can query for more info
      (let [properties (cond->> (remove #{:protocol-services-supported} extended-information-properties)
                         (not refresh?) (remove #(get-device-property dev %)))]
        (when (seq properties)
          (let [result (first (rp/read-properties local-device-id device-id
                                                  [(into [[:device device-id]] properties)]))]
            (doseq [[k v] result]
              (when-not (:error v)
                (set-device-property! dev k v))))))
      (device-extended-information dev))))

(defn- extended-information-retry-ms
  "How long to wait before automatically asking again a device that
  failed 'failures-count' times in a row."
  [local-device failures-count]
  (let [config  (fn [k] (or (get-in local-device [:init-configs k])
                            (get ld/default-configs k)))]
    (min (config :extended-information-max-retry-ms)
         (* (config :extended-information-retry-ms) failures-count))))

(defn- recently-failed?
  "True if we recently failed to fetch the extended information of
  this device. 'local-device' is the local device state (map)."
  [local-device device-id now]
  (when-let [{:keys [at count]} (get-in local-device [::ext-info-failures device-id])]
    (< (- now at)
       (extended-information-retry-ms local-device count))))

(defn- claim-fetch
  "Pure function of the local device state. Try to associate
  'new-claim' ({:promise ... :device ...}) with the remote device.

  The claim is not taken if:
  - the remote device object is no longer the one in the cache (the
    local device was reset, or the cache was cleared);
  - there's already a fetch in flight for this same object;
  - 'automatic?' and the device recently failed to answer."
  [local-device device-id {dev :device :as new-claim} automatic? now]
  (let [claim (get-in local-device [::ext-info-fetch device-id])]
    (cond
      (not (identical? dev (get-in local-device [:remote-devices device-id]))) local-device
      (and claim (identical? dev (:device claim))) local-device
      (and automatic? (recently-failed? local-device device-id now)) local-device
      :else (assoc-in local-device [::ext-info-fetch device-id] new-claim))))

(defn- record-fetch-outcome
  "Pure function of the local device state. Remember if we failed to
  get the complete extended information, unless the remote device
  object is no longer the one in the cache."
  [local-device device-id dev success? now]
  (if (identical? dev (get-in local-device [:remote-devices device-id]))
    (update-in local-device [::ext-info-failures device-id]
               (fn [failures]
                 (when-not success?
                   {:at now :count (inc (:count failures 0))})))
    local-device))

(defn- fetch-role
  "Pure function of the local device state, as it is right after
  `claim-fetch`. What should the caller holding 'dev' and 'new-claim' do?

  :replaced    - 'dev' is no longer the cached object: start over;
  :owner       - we got the claim: fetch;
  :waiter      - a fetch is in flight for this same object: wait for it;
  :not-allowed - nothing to do right now (recently failed)."
  [local-device device-id dev new-claim]
  (let [claim (get-in local-device [::ext-info-fetch device-id])]
    (cond
      ;; This must come first: the claim of a fetch for an object that
      ;; was replaced meanwhile might still be there.
      (not (identical? dev (get-in local-device [:remote-devices device-id]))) :replaced
      (identical? claim new-claim) :owner
      (and claim (identical? dev (:device claim))) :waiter
      :else :not-allowed)))

(defn- retrieve-extended-information!*
  "Only one fetch is in flight per remote device: concurrent callers
  wait for it and share its result.

  Options:
  :refresh?   - ask for everything again, not only what's missing;
  :automatic? - nobody asked for it: do nothing if the device recently
                failed to answer.

  Return the extended information (might be incomplete), or nil."
  [local-device-id device-id {:keys [refresh? automatic?] :as options}]
  (loop [tries 3]
    (when-let [dev (rd local-device-id device-id)]
      (let [fetch-ks  [::ext-info-fetch device-id]
            new-claim {:promise (promise) :device dev}
            ;; In a single atomic step: check that 'dev' is still the
            ;; cached object and take the claim.
            local-device (state/update-in-local-device! local-device-id []
                                                        claim-fetch device-id new-claim automatic?
                                                        (System/currentTimeMillis))]
        (case (fetch-role local-device device-id dev new-claim)
          :owner
          (let [p (:promise new-claim)]
            (try
              (let [[result exception] (try [(fetch-extended-information! local-device-id device-id dev refresh?) nil]
                                            (catch Exception e [nil e]))
                    local-device (state/update-in-local-device! local-device-id []
                                                                record-fetch-outcome device-id dev
                                                                (complete-extended-information? result)
                                                                (System/currentTimeMillis))
                    current-dev  (get-in local-device [:remote-devices device-id])]
                (if (identical? dev current-dev)
                  (do (deliver p result)
                      (when exception (throw exception))
                      result)
                  ;; 'dev' was replaced while we were fetching: what we
                  ;; got is about an object nobody uses anymore. Give
                  ;; what we know about the current one instead.
                  (let [result (some-> current-dev device-extended-information)]
                    (deliver p result)
                    result)))
              (finally
                (deliver p nil) ; no-op if already delivered
                ;; Release our claim, and only ours.
                (state/update-in-local-device! local-device-id fetch-ks
                                               #(when-not (identical? % new-claim) %)))))

          :waiter
          (deref (get-in local-device (conj fetch-ks :promise)) (* 10 60 1000) nil)

          :replaced
          (when (> tries 1) (recur (dec tries)))

          :not-allowed
          (log/debug (str "Device " device-id " recently failed to give its extended "
                          "information; not asking again yet.")))))))

(defnd retrieve-extended-information!
  "Ask the remote device for its extended information (name,
  segmentation, property multiple, etc..) and update it locally, even
  if we already have it.

  Only one fetch is in flight per device: concurrent callers wait for
  it and share its result.

  Return the extended information (might be incomplete if the device
  stopped answering midway), or nil if it couldn't be retrieved."
  [local-device-id device-id]
  (retrieve-extended-information!* local-device-id device-id {:refresh? true}))

(defn- complete-extended-information!
  "Return the extended information we have for the device, after
  trying to get what is missing. Throws only if we end up with
  nothing at all."
  [local-device-id device-id options]
  (let [[result exception] (try [(retrieve-extended-information!* local-device-id device-id options) nil]
                                (catch Exception e [nil e]))]
    (or result
        ;; whatever we have, including what we just learned
        (cached-extended-information local-device-id device-id)
        (when exception (throw exception)))))

(defnd extended-information
  "Return the device extended information that we have cached locally,
  or request it directly to the remote device.

  If the cached information is incomplete, try to complete it; if
  that fails, return what we have."
  [local-device-id device-id]
  (let [cached (cached-extended-information local-device-id device-id)]
    (if (complete-extended-information? cached)
      cached
      (complete-extended-information! local-device-id device-id nil))))

(defn auto-fetch-filter
  "Return the current auto-fetch filter, if any. See `set-auto-fetch-filter!`."
  ([] (auto-fetch-filter nil))
  ([local-device-id]
   (state/get-in-local-device local-device-id [:runtime-options :auto-fetch-filter])))

(defn set-auto-fetch-filter!
  "Set (or remove, with nil) the function deciding for which remote
  devices we automatically fetch the extended information.

  'f' takes a remote device ID and returns true if we are interested
  in this device. It is called for every I-Am received: keep it fast.

  The filter only applies to what bacure does on its own (when an
  I-Am is received and during the network discovery of `boot-up!`).
  Filtered devices are still listed in the remote devices, and
  explicit calls such as `extended-information` or `discover-network`
  are not affected.

  The filter survives a reset of the local device. It can also be
  given to `bacure.core/boot-up!`."
  ([f] (set-auto-fetch-filter! nil f))
  ([local-device-id f]
   (state/update-in-local-device! local-device-id [:runtime-options :auto-fetch-filter]
                                  (constantly f))
   f))

(defn- auto-fetch-extended-information!
  "Fetch the extended information of a remote device, as something we
  do on our own (nobody asked for it). Do nothing if:
  - we already have it;
  - the auto-fetch filter rejects the device;
  - the device recently failed to answer.

  Never throws."
  [local-device-id device-id]
  (try
    (when-not (complete-extended-information?
               (cached-extended-information local-device-id device-id))
      (let [interested? (or (auto-fetch-filter local-device-id) (constantly true))]
        (when (interested? device-id)
          (complete-extended-information! local-device-id device-id {:automatic? true}))))
    (catch Exception e)))

(defn IAm-received-auto-fetch-extended-information
  "Listen to IAm and try to fetch extended-information.

  As this is something we do on our own, it is restricted by the
  auto-fetch filter (see `set-auto-fetch-filter!`) and a device that
  didn't answer is not asked again right away
  (see :extended-information-retry-ms in the local device configs).
  Explicit calls to `extended-information` are not restricted."
  [local-device-id]
  (proxy [DeviceEventAdapter] []
    (iAmReceived [remote-device]
      (auto-fetch-extended-information! local-device-id (.getInstanceNumber remote-device)))))

(defnd remote-devices
  "Return the list of the current remote devices. These devices must
  be in the local table. To scan a network, use `discover-network'."
  [local-device-id]
  (-> (events/cached-remote-devices local-device-id)
      (keys)
      (set)))

(defn- mac-address->device-id
  "Create a map of MAC addresses (human-readable strings) to device IDs for all known remote devices."
  [local-device-id]
  (->> (for [remote-id (remote-devices local-device-id)
             :let [address (-> (rd local-device-id remote-id) .getAddress)
                   mac-string (:mac-address (c/bacnet->clojure address))]]
         [mac-string remote-id])
       (into {})))

(defn- get-network-routers
  "Get the network routers map from the local device transport.
  Returns a map of network-number to OctetString MAC address, or nil if none."
  [local-device-id]
  (some-> (ld/local-device-object local-device-id)
          .getNetwork
          .getTransport
          .getNetworkRouters))

(defn- build-router-info
  "Convert an OctetString MAC address to router info map with device lookup.

  Parameters:
  - local-device-id: The local device ID
  - network-number: The BACnet network number for this router
  - mac-octet-string: The router's MAC address as an OctetString
  - mac->device-id: Map of MAC address strings to device IDs (for device lookup)

  Returns a map with :mac-address, :device-id, and :device-name."
  [local-device-id network-number mac-octet-string mac->device-id]
  (let [mac-address (:mac-address
                     (c/bacnet->clojure
                      (c/clojure->bacnet :address
                                         {:mac-address (vec (.getBytes mac-octet-string))
                                          :network-number network-number})))
        device-id (get mac->device-id mac-address)]
    {:mac-address mac-address
     :device-id   device-id
     :device-name (when device-id (.getName (rd local-device-id device-id)))}))

(defnd network-router
  "Returns information about a single network router by network number.

  Returns a map with router information or nil if no router found:
  {:mac-address \"192.168.1.1:47808\"
   :device-id 1234
   :device-name \"Router\"}"
  [local-device-id network-number]
  (when-let [routers (get-network-routers local-device-id)]
    (when-let [mac-octet-string (get routers (int network-number))]
      (build-router-info local-device-id
                         network-number
                         mac-octet-string
                         (mac-address->device-id local-device-id)))))

(defnd network-routers
  "Returns information about all network routers discovered by the local device.
  Returns a map of network-number to router information:
  {network-number {:mac-address \"192.168.1.1:47808\"
                   :device-id 1234
                   :device-name \"Router\"}}"
  [local-device-id]
  (when-let [routers (get-network-routers local-device-id)]
    (when-not (empty? routers)
      (let [mac->device-id (mac-address->device-id local-device-id)]
        (->> (for [[network-int mac-octet-string] routers]
               [network-int (build-router-info local-device-id
                                               network-int
                                               mac-octet-string
                                               mac->device-id)])
             (into {}))))))

(defnd routing-info
  "Returns routing information for a remote device, including its address and router if on a remote network.

  Returns a map with:
  - :address - Device's own address {:mac-address \"192.168.1.115:47808\" :network-number 3}
  - :routed-by - Router information {:mac-address \"...\" :device-id ... :device-name \"...\"} (omitted if on local network)
  - :routes-to - List of network numbers this device routes to (omitted if device is not a router)

  Keys with nil or empty values are omitted from the result.

  Example return value for a device on a remote network:
  {:address {:mac-address \"192.168.1.115:47808\" :network-number 3}
   :routed-by {:mac-address \"192.168.1.113:47808\" :device-id 1813 :device-name \"Router\"}}

  Example return value for a device on the local network that routes to networks 3 and 5:
  {:address {:mac-address \"192.168.1.113:47808\" :network-number 0}
   :routes-to (3 5)}"
  [local-device-id device-id]
  (let [address (c/bacnet->clojure (.getAddress (rd local-device-id device-id)))
        network-num (:network-number address 0)
        routers (network-routers local-device-id)
        routed-by (get routers network-num)
        routes-to (for [[n-int m] routers
                        :when (= (:device-id m) device-id)]
                    n-int)]
    (->> {:address   address
          :routed-by routed-by
          :routes-to routes-to}
         (remove (fn [[k v]] (or (nil? v)
                                 (and (coll? v) (empty? v)))))
         (into {}))))

(defnd remote-devices-and-names
  "Return a list of vector pair with the device-id and its name.
   -->  ([1234 \"SimpleServer\"])"
  [local-device-id]
  (for [d (remote-devices local-device-id)]
    [d (.getName (rd local-device-id d))]))

(defnd all-extended-information
  "Make sure we have the extended information of every known
   remote devices.

   Can be used some time after the network discovery mechanism, as
   some devices might take a while to answer the WhoIs.

   Remote devices are queried in parallel."
  [local-device-id]
  (let [pool (some-> (ld/get-local-device local-device-id)
                     :-threadpool
                     (claypoole/with-priority 1))]
    (claypoole/upmap pool
                     #(try (extended-information local-device-id %)
                           (catch Exception e))
                     (remote-devices local-device-id))))

(defn- auto-fetch-all-extended-information
  "Same as `all-extended-information`, but as something we do on our
  own: restricted by the auto-fetch filter and the retry delays."
  [local-device-id]
  (let [pool (some-> (ld/get-local-device local-device-id)
                     :-threadpool
                     (claypoole/with-priority 1))]
    (claypoole/upmap pool
                     #(auto-fetch-extended-information! local-device-id %)
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

  ([local-device-id {:keys [min-range max-range dest-port automatic?] :as args}]
   (find-remote-devices local-device-id args)
   (if automatic?
     (auto-fetch-all-extended-information local-device-id)
     (all-extended-information local-device-id))
   (remote-devices local-device-id)))

;; Warning : using `defnd` with `discover-network` would be a breaking
;; change. (Currently the single arity is to specify the
;; local-device-id, but it would be changed to 'tries' with `defnd`)
(defn discover-network
  "Find remote devices and their extended info. By default, will try
   up to 5 time if not a single device answer. Return the list of
   remote-devices.

   Should be called in a future call to avoid `hanging' the program
   while waiting for the remote devices to answer.

   Options:
   :automatic? - The discovery is not an explicit request from a
                 user: only fetch the extended information allowed by
                 the auto-fetch filter (see `set-auto-fetch-filter!`)."
  ([] (discover-network nil))
  ([local-device-id] (discover-network local-device-id 5))
  ([local-device-id tries] (discover-network local-device-id tries nil))
  ([local-device-id tries {:keys [automatic?]}]
   (loop [remaining-tries tries]
     (when (> remaining-tries 0)
       (let [ids (find-remote-devices-and-extended-information local-device-id
                                                               {:automatic? automatic?})]
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
  (if (-> (services-supported local-device-id device-id) :write-property-multiple)
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

;; Unfortunately, it's currently impossible to have multiple local
;; devices on the same IP/port for anything else than sending
;; broadcasts. This is because the OS will dispatch the UDP packets to
;; one of the device and ignore all the others.
;;
;; For example, let's assume we have device 1, 2 and 3.  Device 3
;; sends a read request for [:device 2], but this request is read by
;; device 1.  Device 1 will check its list of objects and correctly
;; respond with a BACnet error, as it doesn't have any [:device 2]
;; object.
;;
;; One possible approach to solve this would be to have a middleware
;; to dispatch the messages to the correct local device.

(def ^:private test-devices-port
  47555) ; Unlikely to mess with existing BACnet network.

(defn- test-device-ip [device-id]
  (str "127.0.0." device-id))

(defn- test-devices-know-each-other? [ids]
  (every? #(= (set (remove #{%} ids))
              (remote-devices %))
          ids))

(defn local-registered-test-devices!
  "Boot up local devices and return their IDs.
  The devices are registered as foreign devices to each other."
  [qty]
  (let [port test-devices-port
        id->ip (into {} (map (juxt :id :ip-address) (ld/local-test-devices! qty port)))]
    ;; Make devices aware of each other
    (doseq [[ld-id _] id->ip] ; current local device
      (doseq [[_ rd-ip] (dissoc id->ip ld-id)] ; all the other devices
        (ld/register-as-foreign-device ld-id rd-ip port 60)))
    (doseq [id (keys id->ip)]
      (ld/i-am-broadcast! id))
    (util/wait-while #(not (test-devices-know-each-other? (keys id->ip))) 500)
    (keys id->ip)))

(defn- register-test-device!
  "Register a test device as a foreign device of another one,
  replacing any previous registration."
  [local-device-id target-id]
  (ld/unregister-as-foreign-device local-device-id)
  ;; Bacnet4j can mistake a late answer to the 'unregister' for the
  ;; answer to the 'register'; try a few times.
  (loop [tries 10]
    (when-not (try (ld/register-as-foreign-device local-device-id (test-device-ip target-id)
                                                  test-devices-port 60)
                   true
                   (catch Exception e
                     (when (= tries 1) (throw e))))
      (Thread/sleep 100)
      (recur (dec tries)))))

(defn reset-registered-test-device!
  "Reset one of the two devices created by
  `local-registered-test-devices!` and wait until they know each
  other again.

  A device acts as the BBMD of the devices registered to it and
  forgets them when it is reset. As for any BBMD restart, the other
  device must register again, or its broadcasts are refused."
  [local-device-id other-device-id]
  (ld/reset-local-device! local-device-id)
  (register-test-device! local-device-id other-device-id)
  (register-test-device! other-device-id local-device-id)
  (util/wait-while #(do (ld/i-am-broadcast! local-device-id)
                        (ld/i-am-broadcast! other-device-id)
                        (Thread/sleep 50)
                        (not (test-devices-know-each-other? [local-device-id other-device-id])))
                   3000)
  nil)
