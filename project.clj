(defproject bacure "1.3.0"
  :description "A Clojure wrapper for the bacnet4j library... and some nice additions."
  :url "https://hvac.io"
  :license {:name "GNU General Public License V3"
            :url "http://www.gnu.org/licenses/gpl-3.0.html"}
  :repositories {"ias-snapshots" "https://maven.mangoautomation.net/repository/ias-snapshot/"
                 "ias-releases" "https://maven.mangoautomation.net/repository/ias-release/"}
  :dependencies [[org.clojure/clojure "1.10.3"]

                 [com.infiniteautomation/bacnet4j "6.0.2"
                  ;; to avoid version range (and repeatability issues), exclude a few dependencies
                  :exclusions [org.slf4j/slf4j-api lohbihler/sero-scheduler]]
                 ;; now require a precise version
                 [org.slf4j/slf4j-api "1.7.33"]
                 [lohbihler/sero-scheduler "1.1.0"
                  :exclusions [org.slf4j/slf4j-api]]

                 ;; Threadpool tools (pmap)
                 [org.clj-commons/claypoole "1.2.2"]

                 ;; logging
                 [org.clojure/tools.logging "1.2.4"]

                 [clj-serial "2.0.5"]
                 [clj-time "0.15.2"]]
  :repl-options {:init-ns user}
  :plugins [[lein-codox "0.10.8"]]
  :codox {;; see https://github.com/weavejester/codox/wiki/Deploying-to-GitHub-Pages
          :output-path "codox"
          :namespaces [#"^bacure\."]
          :source-uri "https://github.com/Frozenlock/bacure/blob/{version}/{filepath}#L{line}"}
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [;; error logs
                                  [com.taoensso/timbre "5.1.0"]
                                  [com.fzakaria/slf4j-timbre "0.3.20"]]
                   :plugins [[lein-ancient "0.6.15"]]}}

  ;; Use a higher loglevel (up to 6) to debug clj-serial
  :jvm-opts ["-Dpurejavacomm.loglevel=0"])
