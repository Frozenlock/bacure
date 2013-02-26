(defproject bacure "0.1.15"
  :description "A Clojure wrapper for the BAC4j library"
  :url "https://bacnethelp.com"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojars.frozenlock/bacnet4j "1.2.4"]
                 [clj-time "0.4.4"]]
  :plugins [[codox "0.6.4"]]
  :codox {:src-dir-uri "https://github.com/Frozenlock/bacure/blob/master"
          :src-linenum-anchor-prefix "L"})
