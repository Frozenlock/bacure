(ns bacure.serial-connection
  (:require [serial.core :as serial]))

;; We store all serial connections as an atom, with the com-port as the key.
;; We'll store the connection object as well as open/closed state so we can make
;; intelligent choices about opening or closing it (to avoid throwing). We have
;; to hold on to these references or we'll get problems with connections left
;; open if we try to delete and re-create a local device.
(defonce serial-connections (atom {}))

(defn- get-connection
  "Returns nil if we haven't created this connection yet."
  [com-port]
  (get-in @serial-connections [com-port :connection]))

(defn connection-opened?
  [com-port]
  (get-in @serial-connections [com-port :opened]))

(defn- ensure-connection-opened!
  "`config` may contain as key/val pairs any of the serial params that
  `serial/open` takes as keyword arguments. If not supplied, `serial/open`
  provides default values. The 'apply / partial' backflips allow us to both keep
  `serial/open`'s defaults and also provide config from our end in our usual
  way (as a map.)"
  [com-port config]

  (if-not (connection-opened? com-port)
    (let [opened-connection (apply (partial serial/open com-port)
                                   (apply concat config))]
      (swap! serial-connections assoc com-port {:connection opened-connection
                                                :opened true}))))

(defn ensure-connection-closed!
  "serial/close! returns nil. NOTE: You must terminate any MS/TP nodes that
  reference this connection first!"
  [com-port]

  (when (connection-opened? com-port)
    (println "Closing serial connection to " com-port)

    (let [closed-connection (some-> (get-connection com-port)
                                    serial/close!)]
      (swap! serial-connections assoc com-port {:connection closed-connection
                                                :opened     false}))))

(defn get-opened-serial-connection!
  "Creates a new connection if this one hasn't been created yet."
  [com-port config]

  (ensure-connection-opened! com-port config)
  (get-connection com-port))
