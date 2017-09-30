(ns bacure.coerce.type.error
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.constructed :as ct])
  (:import [com.serotonin.bacnet4j.type.error
            BACnetError
            BaseError
            ChangeListError
            ConfirmedPrivateTransferError
            ErrorClassAndCode]))



(defmethod clojure->bacnet :bacnet-error
  [_ value]
  (let [{:keys [choice error] :or {choice 1 error {}}} value]
    (BACnetError. choice
                  (clojure->bacnet :error-class-and-code error))))

(defmethod bacnet->clojure BACnetError
  [^BACnetError o]
  {:choice (.getChoice o)
   :error (bacnet->clojure (.getError o))})

;; (defmethod bacnet->clojure BACnetError
;;   [^BACnetError o]
;;   (throw (Exception. (.toString o))))





(defmethod bacnet->clojure BaseError
  [^BaseError o]
  (c/bacnet->clojure (.getError o)))
;; ONE WAY
;; no constructor...
;; (BaseError/createBaseError 1 (com.serotonin.bacnet4j.util.sero.ByteQueue. (byte-array 10)))



(defmethod clojure->bacnet :change-list-error
  [_ value]
  (let [{:keys [error-class-and-code first-failed-element-number]} value]
    (ChangeListError. (clojure->bacnet :error-class-and-code error-class-and-code)
                      (->> (or first-failed-element-number 1)
                           (clojure->bacnet :unsigned-integer)))))

(defmethod bacnet->clojure ChangeListError
  [^ChangeListError o]
  {:error-class-and-code (bacnet->clojure (.getErrorClassAndCode o))
   :first-failed-element-number (bacnet->clojure (.getFirstFailedElementNumber o))})

;;;

;; ONE WAY
;; Unsure how to re-encode the `error-parameters` field.


;; (defmethod clojure->bacnet :confirmed-private-transfer-error
;;   [_ value]
;;   (let [{:keys [error-class-and-code vendor-id service-number error-parameters]} value]
;;     (ConfirmedPrivateTransferError.
;;      (clojure->bacnet :error-class-and-code error-class-and-code)
;;      (clojure->bacnet :unsigned-integer vendor-id)
;;      (clojure->bacnet :unsigned-integer service-number)
;;      ;(clojure->bacnet :encodable service-number)
;;      )))



(defmethod bacnet->clojure ConfirmedPrivateTransferError
  [^ConfirmedPrivateTransferError o]
  {:error-class-and-code (bacnet->clojure (.getErrorClassAndCode o))
   :vendor-id (bacnet->clojure (.getVendorId o))
   :service-number (bacnet->clojure (.getServiceNumber o))
   :error-parameters (bacnet->clojure (.getErrorParameters o))})

;;;

(defmethod clojure->bacnet :error-class-and-code
  [_ value]
  (let [{:keys [error-class error-code] :or {error-class 1 error-code 32}} value]
    (ErrorClassAndCode.
     (clojure->bacnet :error-class error-class)
     (clojure->bacnet :error-code error-code))))

(defmethod bacnet->clojure ErrorClassAndCode
  [^ErrorClassAndCode o]
  {:error-class (bacnet->clojure (.getErrorClass o))
   :error-code (bacnet->clojure (.getErrorCode o))})
