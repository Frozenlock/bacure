(ns bacure.test.core-test
  (:use clojure.test)
  ;; (:require [bacure.core :as b]
  ;;           [bacure.local-device :as ld])
  )



;; (deftest booting-up
;;   (testing "Booting the local BACnet device"
;;     (b/boot-up)
;;     (is (.isInitialized @ld/local-device))
;;     (ld/terminate)
;;     (is (not (.isInitialized @ld/local-device)))))


;; (defn setup! []
;;   (b/boot-up))

;; (defn teardown! []
;;   (ld/terminate))

;; (defmacro with-bacnet-device [& body]
;;   (let [error-var (gensym)]
;;     `(do (setup!)
;;          (try ~@body
;;               (catch Exception ~error-var (prn "Error :" ~error-var))
;;               (finally (teardown!))))))

;; (deftest local-device-objects
;;   (testing "Various local device operations"
;;     (with-bacnet-device
;;       (is (not (seq (ld/local-objects)))) ;; shouldn't have any local objects
;;       (ld/add-or-update-object {:object-identifier [:analog-input 1]
;;                                 :object-name "Analog input 1"
;;                                 :description "Some description"})
;;       (is (-> (count (ld/local-objects)) (= 1))) ;; single local object
;;       (ld/add-or-update-object {:object-identifier [:analog-input 1]
;;                                 :object-name "Analog input 1 again"
;;                                 :description "Some other description"})
;;       ;; object already exsits, should simply update its properties
;;       (is (-> (count (ld/local-objects)) (= 1)))
;;       (is (= "Analog input 1 again" (-> (ld/local-objects) first :object-name)))
;;       ;; remove the local object
;;       (ld/remove-object {:object-identifier [:analog-input 1]})
;;       (is (not (seq (ld/local-objects))))))) ;; shouldn't be empty
      
                                
;;; testing BACnet networks is a little complicated. To simplify
;;; everything, the local device is also the remote device. To do
;;; this, install hamachi/haguichi.

;;; todo ---> add some tests...
