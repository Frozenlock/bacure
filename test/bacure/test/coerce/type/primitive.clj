(ns bacure.test.coerce.type.primitive
  (:use clojure.test)
  (:require [bacure.coerce :refer :all]
            [bacure.coerce.type.primitive :refer :all]))

(deftest test-bitstring
  (let [e (example :bitstring)]
    (is (= (bacnet->clojure (clojure->bacnet :bitstring e))
           e))))

(deftest test-boolean
  (is (= (bacnet->clojure (clojure->bacnet :boolean true))
         true)))

(deftest test-character-string
  (is (= (bacnet->clojure (clojure->bacnet :character-string "hello"))
         "hello")))


(deftest test-date
  (is (= (bacnet->clojure (clojure->bacnet :date "2016-01-16"))
         "2016-01-16")))

(deftest test-double
  (is (= (bacnet->clojure (clojure->bacnet :double 10))
         10.0)))


(deftest test-object-identifier
  (is (= (-> (clojure->bacnet :object-identifier [:analog-input 2])
             (bacnet->clojure))
         [:analog-input 2])))

(deftest test-real
  (is (= (-> (clojure->bacnet :real 12)
             (bacnet->clojure))
         12.0)))

(deftest test-signed-integer
  (is (= (-> (clojure->bacnet :signed-integer 12)
             (bacnet->clojure))
         12)))

(deftest test-time
  (is (= (-> (clojure->bacnet :time "12:20:31.200")
             (bacnet->clojure))
         "12:20:31.200")))

(deftest test-unsigned-16
  (is (= (-> (clojure->bacnet :unsigned-16 12)
             (bacnet->clojure))
         12)))

(deftest test-unsigned-32
  (is (= (-> (clojure->bacnet :unsigned-32 12)
             (bacnet->clojure))
         12)))

(deftest test-unsigned-8
  (is (= (-> (clojure->bacnet :unsigned-8 12)
             (bacnet->clojure))
         12)))

(deftest test-unsigned-integer
  (is (= (-> (clojure->bacnet :unsigned-integer 12)
             (bacnet->clojure))
         12)))

(deftest test-primitive
  (is (= (clojure->bacnet :object-identifier [:analog-input 0])
         (clojure->bacnet :primitive [:analog-input 0])))
  (is (= (clojure->bacnet :unsigned-integer 12)
         (clojure->bacnet :primitive 12)))
  (is (= (clojure->bacnet :real 12.0)
         (clojure->bacnet :primitive 12.0)))
  (is (= (clojure->bacnet :time "12:20:31.200")
         (clojure->bacnet :primitive "12:20:31.200")))
  (is (= (clojure->bacnet :date "2016-01-16")
         (clojure->bacnet :primitive "2016-01-16")))
  (is (= (clojure->bacnet :boolean true)
         (clojure->bacnet :primitive true)))
  (is (= (clojure->bacnet :character-string "abc")
         (clojure->bacnet :primitive "abc"))))


