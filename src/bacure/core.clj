(ns bacure.core
  (:require [bacure.local-device :as ld]
            [bacure.remote-device :as rd]
            [bacure.read-properties :as rp]
            [clojure.walk :as walk]))

   
(defn boot-up
  "Create a local-device, load its config file, initialize it, and
  find the remote devices." []
  (ld/load-local-device-backup)
  (future (rd/discover-network)) true)


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
  (let [configs (ld/local-device-backup)
        results (->> (for [port (range port-min port-max)]
                       (do (ld/reset-local-device {:port port :destination-port port})
                           (rd/find-remote-devices)
                           (Thread/sleep delay)
                           (when-let [devices (seq (rd/remote-devices))]
                             {:port port :devices devices})))
                     (into [])
                     (remove nil?))]
    (ld/reset-local-device configs)
    (future (rd/discover-network))
    results))


;; ================================================================
;; Remote objects related functions
;; ================================================================

(defn remote-object-properties-with-nil
  "Query a remote device and return the properties values
   Example: (object-properties-values 1234 [:analog-input 0] :all)
   -> {:notification-class 4194303, :event-enable .....}

   Both `object-identifiers' and `properties' accept either a single
   item or a collection.

   You probably want to use `remote-object-properties'."
  [device-id object-identifiers properties]
  (let [object-identifiers ((fn[x] (if ((comp coll? first) x) x [x])) object-identifiers)
        properties ((fn [x] (if (coll? x) x [x])) properties)]
    (rp/read-properties-multiple-objects device-id object-identifiers properties)))

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
  ([device-id criteria-map]
     (find-objects device-id criteria-map (remote-objects device-id)))
  ([device-id criteria-map object-identifiers]
     (let [properties (keys criteria-map)
           object-identifiers (or object-identifiers (remote-objects device-id))
           init-map (map #(hash-map :object-identifier %) object-identifiers)
           update-and-filter
           (fn [m p]
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
   {:present-value #(> % 10) :object-name #\"(?i)analog\" :model-name \"GNU\"}

   All remote devices are queried simultaneously (or as much as the
   local-device can allow)."
  ([criteria-map]
     (find-objects-everywhere criteria-map nil))
  ([criteria-map object-identifiers]
     (letfn [(f [device]
               (-> (find-objects device criteria-map object-identifiers)
                   ((fn [x] (when (seq x)
                              [[:device device] x])))))]
       (->> (rd/remote-devices)
            (pmap f) ;; parallel powaaaa
            (into {})))))
