(ns bacure.test.coerce.type.constructed
  (:use clojure.test)
  (:require [bacure.coerce :refer :all]
            [bacure.coerce.type.constructed :refer :all]))

(deftest test-access-rule
  (is (example :access-rule))
  (is (= (-> (clojure->bacnet :access-rule 
                              {:time-range [[:analog-input 0] [[:analog-input 0] :acked-transitions]], 
                               :location [[:analog-input 0] [:analog-input 0]], 
                               :enable true})
             (bacnet->clojure))
         {:time-range [[:analog-input 0] [[:analog-input 0] :acked-transitions]], 
                               :location [[:analog-input 0] [:analog-input 0]], 
          :enable true})))

(deftest test-accumulator-record
  (is (example :accumulator-record))
  (let [accu-map {:timestamp "2016-01-17T21:09:45.180Z", 
                  :present-value 0, 
                  :accumulated-value 0, 
                  :accumulator-status :normal}]
    (is (= (-> (clojure->bacnet :accumulator-record accu-map)
               (bacnet->clojure))
           accu-map))))

(deftest test-action-command
  (is (example :action-command))
  (let [a-c {:property-identifier :present-value
             :device-identifier [:device 1234],
             :post-delay 0,
             :property-value [:present-value 0.0],
             :quit-on-failure false,
             :object-identifier [:analog-input 0],
             :property-array-index 0,
             :priority 0,
             :write-successful false}]
    (is (= (-> (clojure->bacnet :action-command a-c)
               (bacnet->clojure))
           a-c))))

(deftest test-action-list
  (is (example :action-list))
  (let [al [{:property-identifier :present-value
             :device-identifier [:device 1234],
             :post-delay 0,
             :property-value [:present-value 0.0],
             :quit-on-failure false,
             :object-identifier [:analog-input 0],
             :property-array-index 0,
             :priority 0,
             :write-successful false}]]
    (is (= (-> (clojure->bacnet :action-list al)
               (bacnet->clojure))
           al))))


(deftest test-address
  (is (= (-> (clojure->bacnet :address {:mac-address [-64 -88 0 -1 -70 -64], :network-number 0})
             (bacnet->clojure))
         {:mac-address [-64 -88 0 -1 -70 -64], :network-number 0})))

(deftest test-daily-schedule
  (is (example :daily-schedule))
  (let [daily-schedule (bacnet->clojure (example :daily-schedule))]
    (is (= (-> (clojure->bacnet :daily-schedule daily-schedule)
               (bacnet->clojure))
           daily-schedule))))


(deftest test-date-time
  (is (example :date-time))
  (is (= (-> (clojure->bacnet :date-time "2016-01-17T20:48:13.900Z")
             (bacnet->clojure))
         "2016-01-17T20:48:13.900Z")))

(deftest test-event-transition-bits
  (is (example :event-transition-bits))
  (let [tb (assoc (example :event-transition-bits) :to-normal true)]
    (is (= (-> (clojure->bacnet :event-transition-bits tb)
               (bacnet->clojure))
           tb))))


(deftest test-limit-enable
  (is (example :limit-enable))
  (let [tb (assoc (example :limit-enable) :high-limit-enable true)]
    (is (= (-> (clojure->bacnet :limit-enable tb)
               (bacnet->clojure))
           tb))))

(deftest test-log-record
  (is (example :log-record))
  (let [lr (assoc (example :log-record) :value 10.0 :type :real)]
    (is (= (-> (clojure->bacnet :log-record lr)
               (bacnet->clojure))
           lr))))

(deftest test-property-reference
  (is (example :property-reference))
  (is (= (-> (clojure->bacnet :property-reference :present-value)
             (bacnet->clojure))
         :present-value))
  (is (= (-> (clojure->bacnet :property-reference [:present-value 1])
             (bacnet->clojure))
         [:present-value 1])))

(deftest test-object-property-reference
  (is (example :object-property-reference))
  (is (= (-> (clojure->bacnet :object-property-reference [[:analog-input 0] :acked-transitions])
             (bacnet->clojure))
         [[:analog-input 0] :acked-transitions]))
  (is (= (-> (clojure->bacnet :object-property-reference [[:analog-input 0] [:present-value 2]])
             (bacnet->clojure))
         [[:analog-input 0] [:present-value 2]])))


(deftest test-edvice-object-property-reference
  (is (example :device-object-property-reference))
  (is (= (-> (clojure->bacnet :device-object-property-reference [[:device 1234] [[:analog-output 12] :present-value]])
             (bacnet->clojure))
         [[:device 1234] [[:analog-output 12] :present-value]]))
  (is (= (-> (clojure->bacnet :device-object-property-reference [[:device 1234] [[:analog-output 12] [:present-value 2]]])
             (bacnet->clojure))
         [[:device 1234] [[:analog-output 12] [:present-value 2]]])))


(deftest test-device-object-reference
  (is (example :device-object-reference))
  (is (= (-> (clojure->bacnet :device-object-reference [[:device 1234] [:analog-input 0]])
             (bacnet->clojure))
         [[:device 1234] [:analog-input 0]])))

(deftest test-object-types-supported
  (is (example :object-types-supported))
  (let [ots (assoc (example :object-types-supported)
                   :program true :accumulator true)]
    (is (= (-> (clojure->bacnet :object-types-supported ots)
               (bacnet->clojure))
           ots))))

(deftest test-sequence-of
  (is (example :sequence-of))
  (is (= (-> (clojure->bacnet :sequence-of (map (partial clojure->bacnet :property-identifier) 
                                                [:acked-transitions :action]))
             (bacnet->clojure))
         [:acked-transitions :action])))

(deftest test-property-value
  (is (c-simple-property-value nil))
  (is (= (-> (c-property-value [:present-value (clojure->bacnet :real 10.0)])
             (bacnet->clojure))
         (-> (c-property-value {:property-identifier :present-value
                                :property-array-index 0
                                :priority 0
                                :value (clojure->bacnet :real 10.0)})
             (bacnet->clojure))
         [:present-value 10.0]))
  (is (= (-> (clojure->bacnet :property-value* {:property-identifier :present-value
                                                :object-type :analog-input
                                                :value 10})
             (bacnet->clojure))
         [:present-value 10.0])))


(deftest test-read-access-specification
  (is (example :read-access-specification))
  (let [ras [[:analog-input 0] :present-value :description]]
    (is (-> (clojure->bacnet :read-access-specification ras)
            (bacnet->clojure)
            (= ras)))))

(deftest test-recipient
  (is (example :recipient))
  (let [oi [:analog-input 0]]
    (is (-> (clojure->bacnet :recipient oi)
            (bacnet->clojure)
            (= oi))))
  (let [address {:mac-address [-64 -88 0 -1 -70 -64], :network-number 0}]
    (is (-> (clojure->bacnet :recipient address)
            (bacnet->clojure)
            (= address)))))


(deftest test-services-supported
  (is (example :services-supported))
  (let [ss (assoc (example :services-supported)
                  :i-have true :read-property true)]
    (is (= (-> (clojure->bacnet :services-supported ss)
               (bacnet->clojure))
           ss))))



(deftest test-setpoint-reference
  (is (example :setpoint-reference))
  (is (= (-> (clojure->bacnet :setpoint-reference [[:analog-input 0] :acked-transitions])
             (bacnet->clojure))
         [[:analog-input 0] :acked-transitions]))
  (is (= (-> (clojure->bacnet :setpoint-reference [[:analog-input 0] [:present-value 2]])
             (bacnet->clojure))
         [[:analog-input 0] [:present-value 2]])))

;; one way only for now
;; (deftest test-shed-level
;;   (is (example :shed-level))
;;   (let [sl (example :shed-level)]
;;     (is (= (-> (clojure->bacnet :shed-level sl)
;;                (bacnet->clojure))
;;            sl))))


(deftest test-status-flags
  (is (example :status-flags))
  (let [sf (assoc (example :status-flags) :in-alarm true)]
    (is (= (-> (clojure->bacnet :status-flags sf)
               (bacnet->clojure))
           sf))))

(deftest test-time-value
  (is (example :time-value))
  (let [time-value (bacnet->clojure (example :time-value))]
    (is (= (-> (clojure->bacnet :time-value time-value)
               (bacnet->clojure))
           time-value))))

(deftest test-time-stamp
  (is (example :time-stamp))
  (let [timestamp (bacnet->clojure (example :time-stamp))]
    (is (= (-> (clojure->bacnet :time-stamp timestamp)
               (bacnet->clojure))
           timestamp))))

(deftest write-access-specification
  (is (example :write-access-specification))
  (let [data [[:analog-input 0] [[:present-value 10.0]]]]
    (is (= (-> (clojure->bacnet :write-access-specification data)
               (bacnet->clojure))
           data))))

(comment
  (run-tests)
  )
