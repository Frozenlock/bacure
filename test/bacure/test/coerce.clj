(ns bacure.test.coerce
  (:use clojure.test)
  (:require [bacure.coerce :refer :all]))


;;; basic conversion tools

(deftest camel-conversions
  (let [string1 "voltAmperesReactive"
        keyword1 :volt-amperes-reactive]
    (is (= (to-camel (from-camel string1))
           string1))))


(deftest pass-through-conversion
  (is (= (bacnet->clojure {:a 1}) {:a 1}))
  (is (= (bacnet->clojure [1 2 3]) [1 2 3]))
  (is (= (bacnet->clojure nil) nil))
  (is (= (bacnet->clojure "abc") "abc")))

(deftest test-object-to-keyword
  (let [object-type1 (com.serotonin.bacnet4j.type.enumerated.ObjectType. 1)]
    (is (= (object-to-keyword object-type1)
           :analog-output))))












(comment 
;;;;

  (defmacro b-import [subclass]
    `(import [com.serotonin.bacnet4j ~subclass]))

  (b-import type.enumerated.ObjectType)
  (deftest test-object-type
    (doseq [[k n] obj-int-map]
      (is (= (c-object-type k)
             (ObjectType. n)))
      (is (= (bacnet->clojure (ObjectType. n)) k))))


  (b-import type.enumerated.PropertyIdentifier)
  (deftest test-property-identifier
    (doseq [[k n] prop-int-map]
      (is (= (c-property-identifier k)
             (PropertyIdentifier. n)))
      (is (= (bacnet->clojure (PropertyIdentifier. n)) k))))


  (b-import type.enumerated.EngineeringUnits)
  (deftest test-engineering-units
    (doseq [[k n] engineering-units-map]
      (is (= (c-engineering-units k)
             (EngineeringUnits. n)))
      (is (= (bacnet->clojure (EngineeringUnits. n)) k))))


;;;;;;;;;;;;;;;;;;;;
;;; Constructed ;;;;
;;;;;;;;;;;;;;;;;;;;


  (comment
    type.constructed.AccumulatorRecord
    type.constructed.AccumulatorRecord$AccumulatorStatus
    type.constructed.ActionCommand
    type.constructed.ActionList
    type.constructed.Address
    type.constructed.BACnetError
    type.constructed.Choice
    type.constructed.CovSubscription)




  (b-import type.constructed.AccumulatorRecord)
  (deftest test-accumulator
    (let [accumulator-map {:timestamp "2016-01-16T03:51:32.940Z"
                           :present-value 10
                           :accumulated-value 40
                           :accumulator-status :normal}]
      (= (-> accumulator-map c-accumulator bacnet->clojure)
         accumulator-map)))

  (b-import type.constructed.AccumulatorRecord$AccumulatorStatus)
  (deftest test-accumulator-status
    (doseq [[k n] accumulator-status-map]
      (is (= (c-accumulator-status k)
             (AccumulatorRecord$AccumulatorStatus. n)))))
  )
