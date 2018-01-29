(ns bacure.serial-connection
  (:require [serial.core :as serial]
            [bacure.state :as state])
  (:import (java.io OutputStream
                    InputStream)))

;; We store all serial connections as an atom, with the com-port as the key.
;; We'll store the connection object as well as open/closed state so we can make
;; intelligent choices about opening or closing it (to avoid throwing). We have
;; to hold on to these references or we'll get problems with connections left
;; open if we try to delete and re-create a local device.

(defn- get-connection
  "Returns nil if we haven't created this connection yet."
  [com-port]
  (state/get-serial-connection-property com-port [:connection]))

(defn connection-opened?
  [com-port]
  (state/get-serial-connection-property com-port [:opened]))

(defn- ensure-connection-opened!
  "`config` may contain as key/val pairs any of the serial params that
  `serial/open` takes as keyword arguments. If not supplied, `serial/open`
  provides default values. The 'apply / partial' backflips allow us to both keep
  `serial/open`'s defaults and also provide config from our end in our usual
  way (as a map.)"
  [{:keys [com-port] :as config}]

  (if-not (connection-opened? com-port)
    (let [opened-connection (apply (partial serial/open com-port)
                                   (apply concat config))]
      (state/assoc-serial-connection! com-port {:connection opened-connection
                                                :opened true}))))

(defn ensure-connection-closed!
  "serial/close! returns nil. NOTE: You must terminate any MS/TP nodes that
  reference this connection first!"
  [com-port]

  (when (connection-opened? com-port)
    (println "Closing serial connection to " com-port)

    (let [closed-connection (some-> (get-connection com-port)
                                    serial/close!)]
      (state/assoc-serial-connection! com-port {:connection closed-connection
                                                :opened     false}))))

(defn get-opened-serial-connection!
  "Creates a new connection if this one hasn't been created yet."
  [{:keys [com-port] :as config}]

  (ensure-connection-opened! config)
  (get-connection com-port))

(defn get-input-stream
  [connection]

  ^InputStream (.in-stream connection))

(defn get-output-stream
  [connection]

  ^OutputStream (.out-stream connection))
