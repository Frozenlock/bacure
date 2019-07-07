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

