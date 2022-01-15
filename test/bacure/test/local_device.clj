(ns bacure.test.local-device
  (:require [clojure.test :refer :all]
            [bacure.local-device :as ld]))

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

(deftest create-and-remove-objects
  (ld/with-temp-devices
    (let [d-id (ld/new-local-device!)
          object-ids (for [i (range 5)]
                       [:analog-output i])]
      (testing "Create objects"
        ;; initially we should have the 'device' object.
        (is (= 1 (count (ld/local-objects d-id))))

        ;; now add a bunch of analog outputs
        (doseq [object-id object-ids]
          (ld/add-object! d-id {:object-identifier object-id}))

        ;; we should now have 6 objects
        (is (= 6 (count (ld/local-objects d-id))))

        ;; 5 of those should be analog outputs
        (is (= 5 (count (filter #(= % :analog-output) (map :object-type (ld/local-objects d-id)))))))

      (testing "Delete objects"
        ;; finally try to remove them
        (doseq [o-id object-ids]
          (ld/remove-object! d-id o-id))

        (is (= 1 (count (ld/local-objects d-id)))))

      (testing "Automatic instance increment"
        ;; It should be possible to create new objects by providing only :object-type

        (doseq [_ (range 5)]
          (ld/add-object! d-id {:object-type :analog-output}))

        (is (= (for [o (ld/local-objects d-id)
                     :let [[o-type o-inst] (:object-identifier o)]
                     :when (= o-type :analog-output)]
                 o-inst)
               [0 1 2 3 4])))

      (testing "Create object with properties"
        (let [obj-props {:object-type :analog-value
                         :object-name "analog object"
                         :description "Description"
                         :units       :bars}
              new-object (ld/add-object! d-id obj-props)]
          (is (= new-object
                 (assoc obj-props :object-identifier [:analog-value 0]))))))))

(deftest nil-local-device-backup
  (ld/with-temp-devices
    (is (= nil (ld/local-device-backup)))))
