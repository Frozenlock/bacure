(ns bacure.network
  (:use [clojure.string :only (split join)]))

(import 'java.net.InetSocketAddress)
(import java.net.InetAddress java.net.Inet4Address)

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

(defn interfaces-and-ips []
  (let [interfaces (get-interfaces)]
    (->> (for [i interfaces]
           (when-let [ips (seq (ipv4-from-interface i))]
             {:interface (.getName i) :ips ips}))
         (remove nil?))))
        
(defn get-any-ip
  "Return the first IPv4 found." []
  (-> (interfaces-and-ips) first :ips first))

(defn get-broadcast-address
  "Given a local-ip, return the most probable broadcast address"
  [local-ip]
  (join "." (concat (take 3 (split local-ip #"\.")) ["255"])))

(defn resolve-dns
  "Return the IP of a given url, or simply return the IP unchanged"
  [IP-or-url]
  (if-not (re-matches #"\d\d?\d?\.\d\d?\d?\.\d\d?\d?\.\d\d?\d?" IP-or-url)
    (.getHostAddress
     (->> (InetAddress/getAllByName IP-or-url) 
          (filter #(instance? Inet4Address %))
          (first)))
    IP-or-url))
