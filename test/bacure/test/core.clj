(ns bacure.test.core
  (:use clojure.test)
  (:require [bacure.core :as b]
            [bacure.local-device :as ld]))

(deftest device-lifecycle
  (testing "Boot and terminate a local BACnet device"
    (ld/with-temp-devices
      (let [id 1332]
        (b/boot-up! {:device-id id})
        (is (.isInitialized (ld/local-device-object id)))
        (ld/terminate! id)
        (is (not (.isInitialized (ld/local-device-object id))))))))

(deftest reuse-address
  (testing "Multiple BACnet on the same interface"
    (ld/with-temp-devices
      (let [id1 1332
            id2 1333]

        ;; create 2 devices
        (ld/new-local-device! {:device-id id1})
        (ld/new-local-device! {:device-id id2})

        ;; by default it should be possible to initiate multiple devices
        (ld/initialize! id1)
        (ld/initialize! id2)

        (is (and (.isInitialized (ld/local-device-object id1))
                 (.isInitialized (ld/local-device-object id2))))

        ;; check if we can correctly override the default
        (ld/clear-all!)

        (ld/new-local-device! {:device-id id1 :reuse-address false})
        (ld/new-local-device! {:device-id id2 :reuse-address false})

        (with-out-str ;; Don't print out the error message. Perhaps we
                      ;; should update local-device to stop printing
                      ;; an error message like this?
          (try
            (ld/initialize! id1)
            (ld/initialize! id2)
            (catch java.net.BindException e)))

        (is (not (and (.isInitialized (ld/local-device-object id1))
                      (.isInitialized (ld/local-device-object id2)))))))))
