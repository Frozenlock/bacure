(ns bacure.events
  (:require [bacure.coerce :as c]
            [bacure.state :as state])
  (:import com.serotonin.bacnet4j.event.DeviceEventAdapter))

;; Public methods for manipulating cache
(defn cached-remote-devices
  [local-device-id]

  (state/get-local-device-property local-device-id :remote-devices))

(defn clear-cached-remote-devices!
  ([] (clear-cached-remote-devices! nil))

  ([local-device-id]
   (state/assoc-in-local-device! local-device-id :remote-devices #{})))

(defn clear-cached-remote-objects!
  ([] (clear-cached-remote-objects! nil))

  ([local-device-id]
   (state/assoc-in-local-device! local-device-id :remote-objects {})))

;; Event handlers
(defn- add-remote-device-to-cache!
  [local-device-id remote-device]

  (let [remote-device-id (.getInstanceNumber remote-device)
        remote-devices   (-> (cached-remote-devices local-device-id)
                             (conj remote-device-id))]

    (state/assoc-in-local-device! local-device-id :remote-devices remote-devices)))

(defn- add-remote-object-to-cache!
  [local-device-id remote-device remote-object]

  (let [remote-device-id (.getInstanceNumber remote-device)
        remote-object    (c/bacnet->clojure  remote-object)
        object-id        (:object-identifier remote-object)]
    (state/assoc-in-local-device! local-device-id
                                  [:remote-objects remote-device-id object-id]
                                  remote-object)))

(defn- get-unconfirmed-event-listener
  "Listens to IAm, IHave, and COV events and updates a cache with the results"
  [local-device-id]

  (proxy [DeviceEventAdapter] []

    (iAmReceived [remote-device]
      (add-remote-device-to-cache! local-device-id remote-device))

    (iHaveReceived [remote-device remote-object]
      (add-remote-object-to-cache! local-device-id remote-device remote-object))))

;; Local device setup
(defn add-listener!
  [local-device-object local-device-id]

  (let [listener (get-unconfirmed-event-listener local-device-id)]

    (-> local-device-object
        (.getEventHandler)
        (.addListener listener))))
