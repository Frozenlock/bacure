(defproject bacure "1.0.0"
  :description "A Clojure wrapper for the BAC4j library... and some nice additions."
  :url "https://hvac.io"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :repositories [["mangoautomation" "http://mangoautomation.net:9902/maven-repo/"]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.serotonin/bacnet4j "3.2.4"]
                 [clj-time "0.9.0"]]
  :plugins [[lein-codox "0.9.5"]]
  :codox {:doc-paths ["docs"]
          :source-uri "https://github.com/Frozenlock/bacure/blob/master/{filepath}#L{line}"
          ;; :src-linenum-anchor-prefix "L"
          })
