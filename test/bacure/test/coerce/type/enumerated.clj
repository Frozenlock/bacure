(ns bacure.test.coerce.type.enumerated
  (:use clojure.test)
  (:require [bacure.coerce :refer :all]
            [bacure.coerce.type.enumerated :refer :all]))


;; we only test 1 of the generated type. They are all created using
;; the same macro.

(deftest test-generated-object-type
  (is (= (-> (clojure->bacnet :object-type :analog-input)
             (bacnet->clojure))
         :analog-input)))
