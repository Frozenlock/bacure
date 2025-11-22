(ns bacure.test.remote-device
  (:use clojure.test)
  (:require [bacure.local-device :as ld]
            [bacure.read-properties :as rp]
            [bacure.remote-device :as rd]
            [bacure.services :as services]
            [bacure.network :as net]
            [bacure.coerce :as c]))

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

(deftest test-network-router
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
          rd-address (c/bacnet->clojure (.getAddress (rd/rd ld-id rd-id)))
          rd-mac-string (:mac-address rd-address)
          rd-name (.getName (rd/rd ld-id rd-id))]

      (testing "network-router with no routers returns nil"
        (with-redefs [rd/get-network-routers (constantly nil)]
          (is (nil? (rd/network-router ld-id 5)))))

      (testing "network-router for non-existent network returns nil"
        (let [rd-octet-string (.getMacAddress (c/clojure->bacnet :address rd-address))
              mock-routers {5 rd-octet-string}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            ;; Ask for network 10, but only network 5 exists
            (is (nil? (rd/network-router ld-id 10))))))

      (testing "network-router for existing network with known device"
        (let [rd-octet-string (.getMacAddress (c/clojure->bacnet :address rd-address))
              mock-routers {(int 5) rd-octet-string}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-router ld-id 5)]
              (is (map? result))
              (is (= rd-mac-string (:mac-address result)))
              (is (= rd-id (:device-id result)))
              (is (= rd-name (:device-name result)))))))

      (testing "network-router for existing network with unknown device"
        (let [unknown-mac (c/clojure->bacnet :octet-string [10 0 0 1 -70 -64])
              mock-routers {(int 10) unknown-mac}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-router ld-id 10)]
              (is (map? result))
              (is (= "10.0.0.1:47808" (:mac-address result)))
              (is (nil? (:device-id result)))
              (is (nil? (:device-name result))))))))))

(deftest test-network-routers
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
          ;; Get the actual remote device address
          rd-address (c/bacnet->clojure (.getAddress (rd/rd ld-id rd-id)))
          rd-mac-string (:mac-address rd-address)
          rd-name (.getName (rd/rd ld-id rd-id))]

      (testing "network-routers with no routers returns nil"
        (with-redefs [rd/get-network-routers (constantly nil)]
          (is (nil? (rd/network-routers ld-id)))))

      (testing "network-routers with empty routers returns nil"
        (with-redefs [rd/get-network-routers (constantly {})]
          (is (nil? (rd/network-routers ld-id)))))

      (testing "network-routers with real device as router"
        ;; Use the actual remote device's MAC address
        (let [rd-octet-string (.getMacAddress (c/clojure->bacnet :address rd-address))
              mock-routers {5 rd-octet-string}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-routers ld-id)]
              (is (map? result))
              (is (= 1 (count result)))
              (is (contains? result 5))
              (let [router-info (get result 5)]
                ;; Should match the actual device's MAC address
                (is (= rd-mac-string (:mac-address router-info)))
                ;; Should find the device ID and name
                (is (= rd-id (:device-id router-info)))
                (is (= rd-name (:device-name router-info))))))))

      (testing "network-routers with MS/TP router"
        (let [;; Create OctetString for MS/TP station 42
              mstp-mac (c/clojure->bacnet :octet-string [42])
              mock-routers {10 mstp-mac}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-routers ld-id)]
              (is (map? result))
              (is (contains? result 10))
              (let [router-info (get result 10)]
                (is (= "42" (:mac-address router-info))))))))

      (testing "network-routers with IPv6 router"
        (let [;; Create OctetString for IPv6:Port (2001:db8::1:47808)
              ipv6-mac (c/clojure->bacnet :octet-string [32 1 13 -72 0 0 0 0 0 0 0 0 0 0 0 1 -70 -64])
              mock-routers {20 ipv6-mac}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-routers ld-id)]
              (is (map? result))
              (is (contains? result 20))
              (let [router-info (get result 20)]
                (is (= "2001:db8:0:0:0:0:0:1:47808" (:mac-address router-info))))))))

      (testing "network-routers with unknown IPv4 router"
        (let [;; Create OctetString for IPv4:Port not in our device table
              ipv4-mac (c/clojure->bacnet :octet-string [-64 -88 1 99 -70 -64])
              mock-routers {10 ipv4-mac}]
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-routers ld-id)]
              (is (map? result))
              (is (contains? result 10))
              (let [router-info (get result 10)]
                (is (= "192.168.1.99:47808" (:mac-address router-info)))
                ;; Device not in our table
                (is (nil? (:device-id router-info)))
                (is (nil? (:device-name router-info))))))))

      (testing "network-routers with mix of known and unknown routers"
        (let [;; Mix: real device and unknown devices
              rd-octet-string (.getMacAddress (c/clojure->bacnet :address rd-address))
              unknown-mac (c/clojure->bacnet :octet-string [10 0 0 1 -70 -64])
              mstp-mac (c/clojure->bacnet :octet-string [5])
              mock-routers {5 rd-octet-string    ; known device
                            10 unknown-mac        ; unknown IPv4 device
                            15 mstp-mac}]         ; unknown MS/TP device
          (with-redefs [rd/get-network-routers (constantly mock-routers)]
            (let [result (rd/network-routers ld-id)]
              (is (map? result))
              (is (= 3 (count result)))
              (is (contains? result 5))
              (is (contains? result 10))
              (is (contains? result 15))
              ;; Network 5 is the known device - should have device-id and device-name
              (let [known-router (get result 5)]
                (is (= rd-mac-string (:mac-address known-router)))
                (is (= rd-id (:device-id known-router)))
                (is (= rd-name (:device-name known-router))))
              ;; Network 10 is unknown - should have nil device-id and device-name
              (let [unknown-router (get result 10)]
                (is (= "10.0.0.1:47808" (:mac-address unknown-router)))
                (is (nil? (:device-id unknown-router)))
                (is (nil? (:device-name unknown-router))))
              ;; Network 15 is unknown MS/TP - should have nil device-id and device-name
              (let [mstp-router (get result 15)]
                (is (= "5" (:mac-address mstp-router)))
                (is (nil? (:device-id mstp-router)))
                (is (nil? (:device-name mstp-router)))))))))))


(deftest test-routing-info
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]

      (testing "routing-info for device on local network"
        ;; Device on network 0 (local) should have no router
        (let [result (rd/routing-info ld-id rd-id)]
          (is (map? result))
          (is (contains? result :address))
          ;; :routed-by key should be omitted for local devices
          (is (not (contains? result :routed-by)))
          (let [address (:address result)]
            (is (= 0 (:network-number address)))
            (is (string? (:mac-address address))))))

      (testing "routing-info for device on remote network with known router"
        ;; Mock the device to be on network 5 with a known router
        (let [remote-address {:mac-address "192.168.1.115:47808" :network-number 5}
              router-mac "192.168.1.113:47808"
              router-id 1813
              router-name "TestRouter"
              mock-router {:mac-address router-mac
                           :device-id router-id
                           :device-name router-name}
              original-bacnet->clojure c/bacnet->clojure]
          (with-redefs [rd/network-routers (fn [_local-id]
                                             {5 mock-router})
                        c/bacnet->clojure (fn [obj]
                                            (if (instance? com.serotonin.bacnet4j.type.constructed.Address obj)
                                              remote-address
                                              (original-bacnet->clojure obj)))]
            (let [result (rd/routing-info ld-id rd-id)]
              (is (map? result))
              (is (= remote-address (:address result)))
              (is (map? (:routed-by result)))
              (let [router (:routed-by result)]
                (is (= router-mac (:mac-address router)))
                (is (= router-id (:device-id router)))
                (is (= router-name (:device-name router))))))

      (testing "routing-info for device that routes to other networks"
        ;; Device is on local network but acts as router to networks 1 and 2
        (with-redefs [rd/network-routers (fn [_]
                                           {;; Remote device is routing the following networks:
                                            1 {:device-id rd-id}
                                            2 {:device-id rd-id}

                                            ;; Other unused routers
                                            3 {:device-id (inc rd-id)}
                                            4 {:device-id (inc rd-id)}})]
          (let [result (rd/routing-info ld-id rd-id)]
            (is (map? result))
            (is (map? (:address result)))
            ;; :routed-by should be omitted for local network device
            (is (not (contains? result :routed-by)))
            ;; :routes-to should show networks this device routes to
            (is (= [1 2] (:routes-to result))))))))

      (testing "routing-info for device on remote network with unknown router"
        ;; Mock the device to be on network 10, but no router known for that network
        (let [remote-address {:mac-address "10.0.0.50:47808" :network-number 10}
              original-bacnet->clojure c/bacnet->clojure]
          (with-redefs [c/bacnet->clojure (fn [obj]
                                             (if (instance? com.serotonin.bacnet4j.type.constructed.Address obj)
                                               remote-address
                                               (original-bacnet->clojure obj)))]
            (let [result (rd/routing-info ld-id rd-id)]
              (is (map? result))
              (is (= remote-address (:address result)))
              ;; Router should be nil because network 10 has no known router
              (is (nil? (:routed-by result))))))))))
