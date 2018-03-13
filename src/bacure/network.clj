(ns bacure.network
  (:require [bacure.serial-connection :as serial])
  (:import  (java.net InetSocketAddress
                      Inet4Address
                      InetAddress)
            (com.serotonin.bacnet4j.transport.DefaultTransport)
            (com.serotonin.bacnet4j.npdu.ip IpNetwork
                                            IpNetworkBuilder)
            (com.serotonin.bacnet4j.npdu.mstp MasterNode
                                              MstpNetwork
                                              SlaveNode)))

(def default-mstp-config
  {:node-type       :master
   :node-id         1
   :retry-count     3
   :max-info-frames 8
   :max-master-id   127
   :usage-timeout   20})

(defn get-interfaces
  "Return the list of interfaces on this machine."[]
  (enumeration-seq (java.net.NetworkInterface/getNetworkInterfaces)))

(defn ipv4-from-interface
  "Return a list of available IPs from the interface. Remove any
  loopback address.

  As BACnet doesn't support IPv6 yet, just keep IPv4."
  [interface]
  (->> (enumeration-seq (.getInetAddresses interface))
       (filter #(instance? Inet4Address %))
       (remove #(.isLoopbackAddress %))
       (map #(.getHostAddress %))
       (remove nil?)))

(defn get-broadcast-address-of-interface [interface]
  (->> (map #(.getBroadcast %) (.getInterfaceAddresses interface))
       (remove nil?)
       (map #(.getHostAddress %))
       first))


(defn interfaces-and-ips
  "Return a list of interfaces and their IPs.
   ({:interface \"wlan0\", :ips (\"192.168.0.2\")})"[]
  (let [interfaces (get-interfaces)]
    (->> (for [i interfaces]
           (when-let [ips (seq (ipv4-from-interface i))]
             {:interface (.getName i) :ips ips :broadcast-address (get-broadcast-address-of-interface i)
              }))
         (remove nil?))))

(defn get-any-ip
  "Return the first IPv4 found." []
  (-> (interfaces-and-ips) first :ips first))

(defn get-interface-from-ip
  "Given an IP address, return the interface."
  [ip]
  (java.net.NetworkInterface/getByInetAddress (java.net.Inet4Address/getByName ip)))

(defn get-broadcast-address
  "Given a local-ip, return the broadcast address"
  [local-ip]
  (->> (get-interface-from-ip local-ip)
       get-broadcast-address-of-interface))

(defn resolve-dns
  "Return the IP of a given url, or simply return the IP unchanged"
  [IP-or-url]
  (if-not (re-matches #"\d\d?\d?\.\d\d?\d?\.\d\d?\d?\.\d\d?\d?" IP-or-url)
    (.getHostAddress
     (->> (InetAddress/getAllByName IP-or-url)
          (filter #(instance? Inet4Address %))
          (first)))
    IP-or-url))

(defn to-bytes [IP-or-url]
  (-> (InetAddress/getByName IP-or-url)
      (.getAddress)))


;;;

;; bacnet4j transports


(defn ip-network-builder
  "Return an IP network object."
  [{:keys [broadcast-address local-address local-network-number port reuse-address]}]
  (-> (doto (IpNetworkBuilder.)
        (.withBroadcast broadcast-address 0) ;;; <--- need to come back to the network-prefix
        (.withLocalBindAddress local-address)
        (.withLocalNetworkNumber (int local-network-number))
        (.withPort (int port)))
      (.build)))

(defn- create-master-node
  "Return a configured MasterNode object. Master nodes have extra configuration
  they can take that governs their token-passing and poll-for-master behaviors."
  [com-port input-stream output-stream
   {:keys [retry-count max-info-frames max-master-id node-id usage-timeout]
    :as   mstp-config}]

  (doto (MasterNode. com-port input-stream output-stream node-id retry-count)
    (.setMaxInfoFrames max-info-frames)
    (.setMaxMaster     max-master-id)
    (.setUsageTimeout  usage-timeout)))

(defn- create-slave-node
  "Return a configured SlaveNode object."
  [com-port input-stream output-stream node-id]

  (SlaveNode. com-port input-stream output-stream node-id))

(defn- create-mstp-node
  "Based on our configuration, return either a MasterNode or a SlaveNode"
  [{:keys [com-port] :as device-config}
   {:keys [node-type node-id] :as mstp-config}]
  {:pre [(#{:master :slave} node-type)]}

  (let [serial-conn   (serial/get-opened-serial-connection! device-config)
        input-stream  (serial/get-input-stream  serial-conn)
        output-stream (serial/get-output-stream serial-conn)
        node-id       (byte node-id)]
    (case node-type
      :master (create-master-node com-port input-stream output-stream mstp-config)
      :slave  (create-slave-node com-port input-stream output-stream node-id))))

(defn create-mstp-network
  "Return an MstpNetwork object, configured with either a slave or a master node
  for the local-device. This class does not have a dedicated 'builder' like the
  IP network does, so we'll call its constructor directly."
  [{:keys [local-network-number mstp-config]
    :as   device-config}]

  (let [mstp-config (merge default-mstp-config mstp-config)
        node        (create-mstp-node device-config mstp-config)]
    (MstpNetwork. node local-network-number)))
