(ns bacure.coerce.service.acknowledgement
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [bacure.coerce.type.primitive :as p]
            [bacure.coerce.type.enumerated :as e]
            [bacure.coerce.type.constructed :as t-c]
            [bacure.coerce.type.error :as err])
  (:import [com.serotonin.bacnet4j.service.acknowledgement
            AcknowledgementService
            ReadPropertyAck
            ReadPropertyMultipleAck
            CreateObjectAck
            ReadRangeAck]
           com.serotonin.bacnet4j.type.AmbiguousValue))

(defmethod bacnet->clojure AcknowledgementService
  [^ReadPropertyAck o]
  {:choice-id (.getChoiceId o)
   :network-priority (bacnet->clojure (.getNetworkPriority o))})

(defmethod bacnet->clojure ReadPropertyAck
  [^ReadPropertyAck o]
  (let [property-identifier (.getPropertyIdentifier o)
        value (.getValue o)]
    (->> (if (= (class value) AmbiguousValue)
           (.convertTo value (class property-identifier))
           value)
         bacnet->clojure)))


(defmethod bacnet->clojure ReadPropertyMultipleAck
  [^ReadPropertyMultipleAck o]
  (mapcat c/bacnet->clojure (.getListOfReadAccessResults o)))

(defmethod bacnet->clojure CreateObjectAck
  [^CreateObjectAck o]
  {:choice-id (.getChoiceId o)
   :object-identifier (bacnet->clojure (.getObjectIdentifier o))})

(defmethod bacnet->clojure ReadRangeAck
  [^ReadRangeAck o]
  {:object-identifier (bacnet->clojure (.getObjectIdentifier o))
   (bacnet->clojure (.getPropertyIdentifier o)) (bacnet->clojure (.getItemData o))}
  ;;should we add the resultflags?
  )

