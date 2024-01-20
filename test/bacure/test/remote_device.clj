(ns bacure.test.remote-device
  (:use clojure.test)
  (:require [bacure.local-device :as ld]
            [bacure.read-properties :as rp]
            [bacure.remote-device :as rd]
            [bacure.services :as services]
            [bacure.network :as net]))

(deftest basic-read-properties
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
      (testing "Partition array"
        (with-redefs [services/send-request-promise (constantly {:success 10})]
          (let [expected-result (into [] (for [i (range 1 11)]
                                           [[:device rd-id] [:object-list i]]))]
            (is (= expected-result 
                   (rp/expand-array ld-id rd-id [:device rd-id] :object-list)))))))))

(deftest extended-information
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
      ;; initially we shouldn't have this data
      (is (= nil (rd/cached-extended-information ld-id rd-id)))
      ;; now try to retrieve it
      (let [data (rd/extended-information ld-id rd-id)]
        (is (:protocol-services-supported data))
        (is (:object-name data))))))

(deftest read-properties
  (ld/with-temp-devices
    ;; first we create the local devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
      (is (some #{rd-id} (rd/remote-devices ld-id))
          (str "This test requires a device with ID "rd-id " on the network."))
      
      (testing "Read properties" 
        ;; Get the extended information (mostly to know if we can use
        ;; readPropertyMultiple)."
        (is (= (get-in (rd/extended-information ld-id rd-id)
                       [:protocol-services-supported :read-property])
               true))
        ;; we try a few reads

        (let [obj-prop-references [[[:device rd-id] :object-name]
                                   [[:device rd-id] :protocol-version]
                                   [[:device rd-id] :protocol-revision]
                                   [[:device rd-id] [:object-list 1]]]] ; <--- with array index
          (testing "Read individually"
            ;; Reading individually means that each
            ;; object-property-reference is requested separately, then
            ;; merged into objects maps.
            (let [indiv-read (rp/read-individually ld-id rd-id obj-prop-references)
                  {:keys [object-name protocol-version protocol-revision] :as result} (first indiv-read)]
              (is (and object-name protocol-version protocol-revision))
              (is (= [:device rd-id] (some-> result (get :object-list))))

              (testing "Read Property Multiple"
                (let [multi-read (rp/read-property-multiple ld-id rd-id obj-prop-references)
                      {:keys [object-name protocol-version protocol-revision object-list] :as results}
                      (first multi-read)]
                  ;; result of reading individually or in bulk should be the same
                  (is (= indiv-read multi-read))))))))

      (testing "Set property"
        ;; set a remote property
        (let [test-string "This is a test"]
          (rd/set-remote-property! ld-id rd-id [:device rd-id] :description test-string)
          (is (= (:description (first (rp/read-properties ld-id rd-id [[[:device rd-id] :description]])))
                 test-string))))

      (testing "Create/delete object"
        ;; first we delete an objects that doesn't exist. We should get an error
        ;; ;; then we delete once again: we should get an error.
        (is (= (rd/delete-remote-object! ld-id rd-id [:analog-input 1])
               {:error {:error-class :object :error-code :unknown-object}}))

        ;; --- Object creation ----
        ;; We currently can't create many objects because Bacnet4J has limited the acceptable object-types.
        ;; See https://github.com/MangoAutomation/BACnet4J/blob/594226c2890ee7959e08471564a4c3e85ac0a431/src/main/java/com/serotonin/bacnet4j/service/confirmed/CreateObjectRequest.java#L82

        ;; ;; create the new object
        ;; (is (:success (rd/create-remote-object! ld-id rd-id {:object-identifier [:analog-input 1]
        ;;                                                      :object-name "Test analog"
        ;;                                                      :description "This is a test"})))
        
        ;; ;; set the remote property
        ;; (rd/set-remote-property! ld-id rd-id [:analog-input 1] :present-value 10)

        ;; ;; read the remote property; should have the value we just set.
        ;; (is (= (-> (rp/read-properties ld-id rd-id [[[:analog-input 1] :present-value]]) 
        ;;            first
        ;;            (get :present-value))
        ;;        10.0))
        ;; (rd/delete-remote-object! ld-id rd-id [:analog-input 1])        
        
        ;; (is (= (-> (rp/read-properties ld-id rd-id [[[:analog-input 1] :present-value]])
        ;;            (first)
        ;;            :present-value)
        ;;        {:error {:error-class :object :error-code :unknown-object}}))
        )

      (testing "Splitting max read multiple references"
        ;; create a bunch of objects
        (doseq [i (range 10)]
          (ld/add-object! rd-id {:object-identifier [:analog-input i]
                                 :object-name       (str "Analog "i)
                                 :description       "This is a test"}))

        (let [mrmr (.getMaxReadMultipleReferences (rd/rd ld-id rd-id))]
          (.setMaxReadMultipleReferences (rd/rd ld-id rd-id) 2) ;; <--
          ;; maximum 2 references this will force us to read the
          ;; properties by sending multiple requests. They should all
          ;; be merged once they come back.
          (let [results (rp/read-properties ld-id rd-id [[[:analog-input 0] :object-name]
                                                         [[:analog-input 1] :object-name]
                                                         [[:analog-input 3] :object-name]
                                                         [[:analog-input 4] :object-name]
                                                         [[:analog-input 15] :object-name]] ;; <-- will return an error
                                            )]
            (is (= 5 (count results)))
            (is (:error-code (:object-name (last results)))))
          (.setMaxReadMultipleReferences (rd/rd ld-id rd-id) mrmr))

        ;;   ;; delete all objects
        ;;   (doseq [i (range 10)]
        ;;     (rd/delete-remote-object! ld-id rd-id [:analog-input i])))
        ))))
