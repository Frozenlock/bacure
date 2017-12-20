(ns user
  (:require [bacure.remote-device :as rd]
            [bacure.local-device :as ld]
            [bacure.core :as bc-core]
            [bacure.read-properties :as bc-read]
            [bacure.services :as services]
            [clojure.repl :refer :all]
            [serial.core]
            [serial.util]))

