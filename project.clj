(defproject bacure "0.5.0"
  :description "A Clojure wrapper for the BAC4j library... and some nice additions."
  :url "https://hvac.io"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojars.frozenlock/bacnet4j "1.3.11"]
                 [clj-time "0.7.0"]
                 [org.clojure/core.cache "0.6.4"]
                 [org.clojars.frozenlock/doevery "0.1.1"]]
  :plugins [[codox "0.8.6"]]
  :codox {:src-dir-uri "https://github.com/Frozenlock/bacure/blob/master"
          :src-linenum-anchor-prefix "L"})
