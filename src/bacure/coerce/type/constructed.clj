(ns bacure.coercion.type.constructed
  (:require [bacure.coercion.coerce :as c]
            [bacure.coerceion.type.primitive :as p])
  (:import [com.serotonin.bacnet4j.type.constructed
            AccumulatorRecord
            AccumulatorRecord$AccumulatorStatus]))

;;;

(declare c-date-time)
(defn c-accumulator [m]
  (let [{:keys [timestamp present-value accumulated-value accumulator-status]} m]
    (AccumulatorRecord. (c-date-time timestamp)
                        (c-unsigned present-value)
                        (c-unsigned accumulated-value)
                        (c-accumulator-status accumulator-status))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.AccumulatorRecord
  [^AccumulatorRecord o]
  {:timestamp (bacnet->clojure (.getTimestamp o))
   :present-value (bacnet->clojure (.getPresentValue o))
   :accumulated-value (bacnet->clojure (.getAccumulatedValue o))
   :accumulator-status (bacnet->clojure (.getAccumulatorStatus o))})

;;;


(def accumulator-status-map
  (c/subclass-to-map AccumulatorRecord$AccumulatorStatus.))

(defn c-accumulator-status [value]
  (let [subclass-map accumulator-status-map]
    (AccumulatorRecord$AccumulatorStatus. (or (c/map-or-num subclass-map value) 0))))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.constructed.AccumulatorRecord$AccumulatorStatus
  [^AccumulatorRecord$AccumulatorStatus o]
  (first (->> accumulator-status-map
              (filter (comp #{(.intValue o)} val))
              (map key))))
