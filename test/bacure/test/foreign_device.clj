(ns bacure.test.foreign-device
  (:require [bacure.local-device :as ld]
            [bacure.remote-device :as rd]
            [clojure.test :refer :all]))

(deftest foreign-devices
  (let [port 47555]
    (ld/with-temp-devices
      (let [ids-and-addr (ld/local-test-devices! 2 port)]
        ;; we shouldn't see any remote device
        (is (-> (rd/remote-devices 2)
                (empty?)))
        ;; send broadcasts... we still shouldn't see any devices
        (ld/i-am-broadcast! 2)
        (Thread/sleep 50)
        (is (-> (rd/remote-devices 2)
                (empty?)))
        ;; now register as a foreign device
        (ld/register-as-foreign-device 1 "127.0.0.2" port 60)
        (ld/i-am-broadcast! 1)
        (Thread/sleep 50)
        ;; at this point the device 2 should be aware that device 1
        ;; exists.
        (is (-> (rd/remote-devices 2)
                (count)
                (= 1)))))))
