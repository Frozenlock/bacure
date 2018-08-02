(defproject bacure "1.1.0-alpha2"
  :description "A Clojure wrapper for the bacnet4j library... and some nice additions."
  :url "https://hvac.io"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :repositories {"ias-snapshots" "https://maven.mangoautomation.net/repository/ias-snapshot/"
                 "ias-releases" "https://maven.mangoautomation.net/repository/ias-release/"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.serotonin/bacnet4j "4.1.6"]
                 [clj-serial "2.0.3"]
                 [clj-time "0.14.2"]
                 [org.slf4j/slf4j-log4j12 "1.8.0-beta0"]]
  :repl-options {:init-ns user}
  :plugins [[lein-codox "0.9.5"]]
  :codox {:doc-paths ["docs"]
          :source-uri "https://github.com/Frozenlock/bacure/blob/master/{filepath}#L{line}"
          ;; :src-linenum-anchor-prefix "L"
          }
  :profiles {:dev {:source-paths ["dev"]
                   :plugins [[lein-ancient "0.6.15"]]}}

  ;; Use a higher loglevel (up to 6) to debug clj-serial
  :jvm-opts ["-Dpurejavacomm.loglevel=0"]

  )

;; Uncomment if problem donwloading from Mango's repo.
;; (Tho it's a very bad idea)
;; Mangoautomation's Maven server doesn't use TLS?!
;(require 'cemerick.pomegranate.aether)
;; (cemerick.pomegranate.aether/register-wagon-factory!
;;    "http" #(org.apache.maven.wagon.providers.http.HttpWagon.))
