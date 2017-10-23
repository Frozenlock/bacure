(ns bacure.test.coerce.type.error
  (:use clojure.test)
  (:require [bacure.coerce :refer :all]
            [bacure.coerce.type.error :refer :all]))



(deftest test-bacnet-error
  (let [e (example :bacnet-error)]
    (is (= (bacnet->clojure (clojure->bacnet :bacnet-error e))
           e))))

(deftest test-change-list-error
  (let [e (example :change-list-error)]
    (is (= (bacnet->clojure (clojure->bacnet :change-list-error e))
           e))))


(deftest test-error-class-and-code
  (let [e (example :error-class-and-code)]
    (is (= (bacnet->clojure (clojure->bacnet :error-class-and-code e))
           e))))
