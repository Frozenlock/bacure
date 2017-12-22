(ns bacure.test.foreign-device
  (:use clojure.test)
  (:require [bacure.local-device :as ld]
            [bacure.remote-device :as rd]))

(defn generate-local-devices [qty]
  (let [ids-and-addr (for [i (range 1 (inc qty))]
                       {:id i :ip-address (str "127.0.0."i)})]
    (doseq [m ids-and-addr]
      (let [id (:id m)]
        (ld/new-local-device!
         {:device-id id
          :local-address (:ip-address m)
          :broadcast-address "127.0.0.255"})
        (ld/initialize! id)))
    ids-and-addr))

(deftest foreign-devices
  (ld/with-temp-devices
    (let [ids-and-addr (generate-local-devices 2)]
      ;; we shouldn't see any remote device
      (is (-> (rd/remote-devices 2)
              (empty?)))
      ;; send broadcasts... we still shouldn't see any devices
      (ld/i-am-broadcast! 2)
      (Thread/sleep 50)
      (is (-> (rd/remote-devices 2)
              (empty?)))
      ;; now register as a foreign device
      (ld/register-as-foreign-device 1 "127.0.0.2" 47808 60)
      (ld/i-am-broadcast! 1)
      (Thread/sleep 50)
      ;; at this point the device 2 should be aware that device 1
      ;; exists.
      (is (-> (rd/remote-devices 2)
              (count)
              (= 1))))))

