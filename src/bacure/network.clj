(ns bacure.network
  (:use [clojure.string :only (split join)]))

(import 'java.net.InetSocketAddress)
(import java.net.InetAddress java.net.Inet4Address)

(defn get-ip
  "Return the first IPv4 address which IS NOT the localhost (\"127.0.0.1\")"
  []
  (let [IP-list
        (for [inter (enumeration-seq (java.net.NetworkInterface/getNetworkInterfaces))]
          (for [ip (enumeration-seq (.getInetAddresses inter))]
            (.getHostAddress ip)))
        IPv4-list (map #(re-matches #"\d\d?\d?\.\d\d?\d?\.\d\d?\d?\.\d\d?\d?" %)
                       (flatten IP-list))]
    (first (remove #(or (= "127.0.0.1" %) (= nil %)) IPv4-list))))

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
