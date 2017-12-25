(ns bacure.events
  (:require [bacure.coerce :as c]
            [bacure.state :as state]
            [bacure.util :as util])
  (:import com.serotonin.bacnet4j.event.DeviceEventAdapter))

(def default-cov-process-id 1)

;; Public methods for accessing cache
(defn cached-remote-devices
  ([] (cached-remote-devices nil))

  ([local-device-id]
   (state/get-in-local-device local-device-id [:remote-devices])))

(defn cached-remote-objects
  ([] (cached-remote-objects nil))

  ([local-device-id]
   (state/get-in-local-device local-device-id [:remote-objects])))

(defn all-cached-cov-events
  ([] (all-cached-cov-events nil))

  ([local-device-id]
   (state/get-in-local-device local-device-id [:cov-events])))

(defn cached-cov-events
  ([] (cached-cov-events nil default-cov-process-id))

  ([process-identifier] (cached-cov-events nil process-identifier))

  ([local-device-id process-identifier]
   (state/get-in-local-device local-device-id [:cov-events process-identifier])))

;; Public methods for manipulating cache
(defn clear-cached-remote-devices!
  ([] (clear-cached-remote-devices! nil))

  ([local-device-id]
   (state/assoc-in-local-device! local-device-id [:remote-devices] #{})))

(defn clear-cached-remote-objects!
  ([] (clear-cached-remote-objects! nil))

  ([local-device-id]
   (state/assoc-in-local-device! local-device-id [:remote-objects] {})))

(defn clear-all-cached-cov-events!
  ([] (clear-all-cached-cov-events! nil))

  ([local-device-id]
   (state/assoc-in-local-device! local-device-id [:cov-events] {})))

(defn clear-cached-cov-events!
  ([] (clear-cached-cov-events! nil default-cov-process-id))

  ([process-identifier] (clear-cached-cov-events! nil process-identifier))

  ([local-device-id process-identifier]
   (state/assoc-in-local-device! local-device-id [:cov-events process-identifier] [])))

;; Event handlers
(defn- add-remote-device-to-cache!
  [local-device-id remote-device]

  (let [remote-device-id (.getInstanceNumber remote-device)
        remote-devices   (-> (cached-remote-devices local-device-id)
                             (assoc remote-device-id remote-device))]
    
    (state/assoc-in-local-device! local-device-id [:remote-devices] remote-devices)))

(defn- add-remote-object-to-cache!
  [local-device-id remote-device remote-object]

  (let [remote-device-id (.getInstanceNumber remote-device)
        remote-object    (c/bacnet->clojure  remote-object)
        object-id        (:object-identifier remote-object)]
    (state/assoc-in-local-device! local-device-id
                                  [:remote-objects remote-device-id object-id]
                                  remote-object)))

(defn- add-cov-event-to-cache!
  [local-device-id subscriber-process-identifier initiating-device-identifier
   monitored-object-identifier time-remaining list-of-values]

  (let [subscriber-process-identifier (c/bacnet->clojure subscriber-process-identifier)
        initiating-device-identifier  (c/bacnet->clojure initiating-device-identifier)
        monitored-object-identifier   (c/bacnet->clojure monitored-object-identifier)
        time-remaining                (c/bacnet->clojure time-remaining)
        values                        (->> (c/bacnet->clojure list-of-values)
                                           (into {}))

        events-cache-ks [:cov-events subscriber-process-identifier]

        event  (util/mapify initiating-device-identifier monitored-object-identifier
                            time-remaining values)
        events (state/get-in-local-device local-device-id events-cache-ks [])]

    (state/assoc-in-local-device! local-device-id events-cache-ks (conj events event))))

(defn- get-unconfirmed-event-listener
  "Listens to IAm, IHave, and COV events and updates a cache with the results"
  [local-device-id]

  (proxy [DeviceEventAdapter] []

    (iAmReceived [remote-device]
      (add-remote-device-to-cache! local-device-id remote-device))

    (iHaveReceived [remote-device remote-object]
      (add-remote-object-to-cache! local-device-id remote-device remote-object))
    
    ;; This was useful for debugging, but it's not needed for any features so far
    ;;(requestReceived [from service]
    ;;  (apply prn [(c/bacnet->clojure from) (c/bacnet->clojure service)]))

    (covNotificationReceived [subscriber-process-identifier initiating-device-identifier
                              monitored-object-identifier time-remaining list-of-values]
      (add-cov-event-to-cache! local-device-id subscriber-process-identifier
                               initiating-device-identifier monitored-object-identifier
                               time-remaining list-of-values))))

;; Local device setup
(defn add-listener!
  [local-device-object local-device-id]

  (let [listener (get-unconfirmed-event-listener local-device-id)]

    (-> local-device-object
        (.getEventHandler)
        (.addListener listener))))
