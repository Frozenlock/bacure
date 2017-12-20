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
  [state k sub-k]

  (-> (get-value state k) sub-k))

(defn- assoc-value!
  [state k v]

  (swap! state assoc k v))

(defn- assoc-in-value!
  [state k sub-ks v]

  (let [k  (get-key state k)
        ks (if (vector? sub-ks)
             (concat [k] sub-ks)
             [k sub-ks])]
    (swap! state assoc-in ks v)))

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

(def get-local-device-id (partial get-key local-devices))
(def get-local-device (partial get-value local-devices))
(def get-local-device-property (partial get-in-value local-devices))
(def assoc-in-local-device! (partial assoc-in-value! local-devices))
(def assoc-local-device! (partial assoc-value! local-devices))
(def dissoc-local-device! (partial dissoc-value! local-devices))
(def clear-local-devices! (partial clear-all-values! local-devices {}))

;; Serial connections
(defonce serial-connections (atom {}))

(def assoc-serial-connection! (partial assoc-value! serial-connections))
(def get-serial-connection-property (partial get-in-value serial-connections))
