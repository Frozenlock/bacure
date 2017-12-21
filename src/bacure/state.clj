(ns bacure.state)

;; Generic methods
(defn- set-value!
  [state v]

  (reset! state v))

(defn- get-key
  [state k]
  (or k (some-> @state first key)))

(defn- get-value
  "Return the local-device associated with the device-id. If device-id
  is nil, simply return the first found."
  [state k]

  (->> (get-key state k)
       (get @state)))

(defn- get-in-value
  [state k sub-ks]
  (-> (get-value state k)
      (get-in sub-ks)))

(defn- assoc-value!
  [state k v]

  (swap! state assoc k v)
  v)

(defn- assoc-in-value!
  "Assoc-in the value 'v' at 'sub-ks'."
  [state k sub-ks v]
  (let [ks (cons (get-key state k) sub-ks)]
    (swap! state assoc-in ks v))
  v)

(defn- dissoc-value!
  [state k]

  (swap! state dissoc k))

(defn- clear-all-values!
  [state default]

  (reset! state default))

;; Requests
(defonce request-response (atom nil))

(def set-request-response! (partial set-value! request-response))

;; Local devices
(defonce local-devices (atom {}))

;(def get-local-device-id (partial get-key local-devices)) ;; not yet used

(defn get-local-device
  "Return the local device"
  [device-id]
  (get-value local-devices device-id))

(defn get-in-local-device ;; previously 'get-local-device-property'
  "Return the value"
  [device-id ks]
  (get-in-value local-devices device-id ks))

(defn assoc-in-local-device!
  "Set the value"
  [device-id ks v]
  (assoc-in-value! local-devices device-id ks v))

(defn assoc-local-device!
  "Assoc a local device with the given key"
  [device-id local-device]
  (assoc-value! local-devices device-id local-device))

(defn dissoc-local-device!
  "Dissoc the local device associated with the given key, if any."
  [device-id]
  (dissoc-value! local-devices device-id))

(defn clear-local-devices!
  "Remove all traces of local devices in the local cache.
  WARNING: Doesn't clean up; ports might still be bound to the removed
  devices."
  []
  (clear-all-values! local-devices {}))

;(def get-local-device (partial get-value local-devices))
;(def get-local-device-property (partial get-in-value local-devices))
;(def assoc-in-local-device! (partial assoc-in-value! local-devices))
;(def assoc-local-device! (partial assoc-value! local-devices))
;(def dissoc-local-device! (partial dissoc-value! local-devices))
;(def clear-local-devices! (partial clear-all-values! local-devices {}))

;; Serial connections
(defonce serial-connections (atom {}))

(def assoc-serial-connection! (partial assoc-value! serial-connections))
(def get-serial-connection-property (partial get-in-value serial-connections))
