(ns bacure.test.coerce.obj
  (:require [bacure.coerce :as c]
            [bacure.coerce.obj :as obj]
            [bacure.local-device :as ld]
            [clojure.test :refer :all]))

(deftest bacnet-object
  (ld/with-temp-devices
    (let [oid [:analog-input 3]
          id (ld/new-local-device!)
          object-map {:object-identifier oid
                      :object-name       "My object name"}
          object (ld/add-object! id object-map)]
       (is (= (select-keys (c/bacnet->clojure object) [:object-identifier :object-name])
              object-map)))))

(deftest encode-property-value
  (let [weekly-schedule (repeat 7 [{:time "14:0:0.0" :value 1}{:time "15:0:0.0" :value 1}])]
    (is (= weekly-schedule
           (-> (obj/encode-property-value :schedule :weekly-schedule weekly-schedule)
               (c/bacnet->clojure))))))
