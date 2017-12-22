(ns bacure.coerce.service.confirmed
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]])
  (:import [com.serotonin.bacnet4j.service.confirmed
            DeviceCommunicationControlRequest$EnableDisable]))

(c/enumerated-converter DeviceCommunicationControlRequest$EnableDisable)

