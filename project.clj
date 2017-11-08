(defproject bacure "1.1.0-alpha"
  :description "A Clojure wrapper for the bacnet4j library... and some nice additions."
  :url "https://hvac.io"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :repositories {"ias-snapshots" "https://maven.mangoautomation.net/repository/ias-snapshot/"
                 "ias-releases" "https://maven.mangoautomation.net/repository/ias-release"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.serotonin/bacnet4j "4.0.1"]
                 [clj-serial "2.0.3"]
                 ;[org.clojars.frozenlock/bacnet4j "3.2.4-4"]
                 [clj-time "0.12.0"]]
  :repl-options {:init-ns user}
  :plugins [[lein-codox "0.9.5"]]
  :codox {:doc-paths ["docs"]
          :source-uri "https://github.com/Frozenlock/bacure/blob/master/{filepath}#L{line}"
          ;; :src-linenum-anchor-prefix "L"
          }
  :profiles {:dev {:source-paths ["dev"]}})
