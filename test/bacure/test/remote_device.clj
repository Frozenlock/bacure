(ns bacure.test.remote-device
  (:use clojure.test)
  (:require [bacure.local-device :as ld]
            [bacure.read-properties :as rp]
            [bacure.remote-device :as rd]))


(defn init-test-local-device! [local-device-id]
  ;; adjust the broadcast-address as needed.
  (ld/new-local-device! {:device-id local-device-id :broadcast-address "192.168.1.255"})
  (ld/initialize! local-device-id))


(deftest basic-read-properties
  (ld/with-temp-devices
    (let [ld-id 1133
          rd-id 1338]
      (init-test-local-device! ld-id)
      (testing "Partition array"
        (with-redefs [rp/send-request-promise (constantly {:success 10})]
          (let [expected-result (into [] (for [i (range 1 11)]
                                           [[:device rd-id] [:object-list i]]))]
            (is (= expected-result 
                   (rp/partition-array ld-id rd-id [:device rd-id] :object-list)))))))))

(deftest read-properties
  (ld/with-temp-devices
    (let [ld-id 1133  ;; test local device-id
          rd-id 1338]
      ;; first we create a local device
      (init-test-local-device! ld-id)
      (is (some #{rd-id} (rd/discover-network ld-id)) 
          (str "This test requires a device with ID "rd-id " on the network."))
      ;(println (rp/read-properties ld-id rd-id [[[:device rd-id] :object-name]]))
      (testing "Read properties" 
        ;; after discovering the network, we should have remote device
        ;; extended information (mostly to know if we can use
        ;; readPropertyMultiple)."
        (is (= (get-in (rd/extended-information ld-id rd-id) 
                       [:protocol-services-supported :confirmed-text-message])
                 true))
        ;; we try a few reads
        
        (testing "Read individually"
          ;; Reading individually means that each
          ;; object-property-reference is requested separately, then
          ;; merge into objects maps.

          (let [{:keys [object-name protocol-version protocol-revision] :as result}
                (first (rp/read-individually ld-id rd-id [[[:device rd-id] :object-name]
                                                          [[:device rd-id] :protocol-version]
                                                          [[:device rd-id] :protocol-revision]
                                                          [[:device rd-id] [:object-list 1]]]))] ;; <--- with array index
            (is (and object-name protocol-version protocol-revision))
            ;; check if we got the property with array index back
            (is (= [:device rd-id] (some-> result (get [:object-list 1]))))))

        (testing "Read Property Multiple"
          (let [{:keys [object-name protocol-version protocol-revision] :as results}
                (first (rp/read-property-multiple ld-id rd-id [[[:device rd-id] :object-name]
                                                               [[:device rd-id] :protocol-version]
                                                               [[:device rd-id] [:object-list 6]]]))]
            (is (and object-name protocol-version))
            (is (= (get-in results [:object-list :error :error-code])
                   :invalid-array-index))))
        
        (let [{:keys [object-name protocol-version]}
              (first
               (rp/read-properties ld-id rd-id [[[:device rd-id] :object-name :protocol-version :protocol-revision]]))]          
          (is (and object-name
                   protocol-version))))

      (testing "Create/delete object"
        ;; first we delete the object to make sure it isn't there.
        (rd/delete-remote-object! ld-id rd-id [:analog-input 1])

        ;; then we delete once again: we should get an error.
        (is (= (rd/delete-remote-object! ld-id rd-id [:analog-input 1])
               {:error {:error-class :object :error-code :unknown-object}}))

        ;; create the new object
        (is (:success (rd/create-remote-object ld-id rd-id {:object-identifier [:analog-input 1]
                                                            :object-name "Test analog"
                                                            :description "This is a test"})))
        
        ;; set the remote property
        (rd/set-remote-property ld-id rd-id [:analog-input 1] :present-value 10)

        ;; read the remote property; should have the value we just set.
        (is (= (-> (rp/read-properties ld-id rd-id [[[:analog-input 1] :present-value]]) 
                   first
                   (get :present-value))
               10.0))
        (rd/delete-remote-object! ld-id rd-id [:analog-input 1])        
        
        (is (= (-> (rp/read-properties ld-id rd-id [[[:analog-input 1] :present-value]])
                   (first)
                   :present-value)
               {:error {:error-class :object :error-code :unknown-object}})))

      (testing "Splitting max read multiple references"
        ;; create a bunch of objects
        (doseq [i (range 10)]          
          (rd/create-remote-object ld-id rd-id {:object-identifier [:analog-input i]
                                                :object-name (str "Analog "i)
                                                :description "This is a test"}))

        (let [mrmr (.getMaxReadMultipleReferences (rd/rd ld-id rd-id))]
          (.setMaxReadMultipleReferences (rd/rd ld-id rd-id) 2) ;; <--
          ;; maximum 2 references this will force us to read the
          ;; properties by sending multiple requests. They should all
          ;; be merged once they come back.          
          (is (= 5 (count (rp/read-properties ld-id rd-id [[[:analog-input 0] :object-name]
                                                           [[:analog-input 1] :object-name]
                                                           [[:analog-input 3] :object-name]
                                                           [[:analog-input 4] :object-name]
                                                           [[:analog-input 15] :object-name]] ;; <-- will return an error
                                              ))))
          (.setMaxReadMultipleReferences (rd/rd ld-id rd-id) mrmr))

        ;(print (rp/read-properties ld-id rd-id [[[:device rd-id] :object-list]]))

        ;; delete all objects
        (doseq [i (range 10)]
          (rd/delete-remote-object! ld-id rd-id [:analog-input i])))

      

      )))
