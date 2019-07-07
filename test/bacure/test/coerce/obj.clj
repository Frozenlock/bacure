(ns bacure.test.coerce.obj
  (:require [bacure.coerce :as c]
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
