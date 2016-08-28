(ns bacure.coerce.type.error
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.constructed :as ct])
  (:import [com.serotonin.bacnet4j.type.error
            BaseError]))


;; ONE WAY

(defmethod bacnet->clojure BaseError
  [^BaseError o]
  (c/bacnet->clojure (.getError o)))
