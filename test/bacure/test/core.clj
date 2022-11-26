(ns bacure.test.core
  (:require [bacure.core :as b]
            [bacure.local-device :as ld]
            [clojure.test :refer :all]))

(deftest device-lifecycle
  (testing "Boot and terminate a local BACnet device"
    (ld/with-temp-devices
      (let [id 1332]
        (b/boot-up! {:device-id id})
        (is (.isInitialized (ld/local-device-object id)))
        (ld/terminate! id)
        (is (not (.isInitialized (ld/local-device-object id))))))))

(deftest device-update
  (testing "Boot and update a local device"
    (ld/with-temp-devices
      (let [id 1332]
        (b/boot-up! {:device-id id})
        (b/boot-up! {:device-id id :broadcast-address "255.255.255.255"})
        (is (.isInitialized (ld/local-device-object id)))))))
