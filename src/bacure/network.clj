(ns bacure.network
  (:require [serial.core :as serial])
  (:import  (java.net InetSocketAddress
                      Inet4Address
                      InetAddress)
            (com.serotonin.bacnet4j.transport.DefaultTransport)
            (com.serotonin.bacnet4j.npdu.ip IpNetwork
                                            IpNetworkBuilder)
            (com.serotonin.bacnet4j.npdu.mstp MasterNode
                                              MstpNetwork
                                              SlaveNode)))

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
  [{:keys [broadcast-address local-address local-network-number port reuse-address]
    :or {reuse-address true, local-network-number 0
         local-address IpNetwork/DEFAULT_BIND_IP
         port IpNetwork/DEFAULT_PORT} :as args}]
  (-> (doto (IpNetworkBuilder.)
        (.withBroadcast broadcast-address 0) ;;; <--- need to come back to the network-prefix
        (.withLocalBindAddress local-address)
        (.withLocalNetworkNumber (int local-network-number))
        (.withPort (int port)))
      (.build)))

(defn mstp-network
  "Return a MSTP network object, configured as either a slave or a master node.
  This class does not have a dedicated 'builder' like the IP network does. "
  [serial-port {:keys [node-type node-id local-network-number retry-count]
                :or {node-type            :master
                     node-id              1
                     local-network-number 0
                     retry-count          3}}]
  {:pre [(#{:master :slave} node-type)]}

  (let [input-stream  (.in-stream  serial-port)
        output-stream (.out-stream serial-port)
        node    (case node-type
                  :master (MasterNode. input-stream output-stream (byte node-id) retry-count)
                  :slave  (SlaveNode.  input-stream output-stream (byte node-id)))]
    (MstpNetwork. node local-network-number)))
