(ns bacure.local-save
  (:require [clojure.java.io :as io]))


;; (def home-path
;;   "A writable path where we can store files."
;;   (str (System/getProperty "user.home")"/bacure/"))

(def path "")

(defn mkdir-spit
  "Try to make the directories leading to the file if they don't
  already exists." [f content]
  (clojure.java.io/make-parents f)
  (spit f content))

(defn make-program-path [name]
  (str path name ".clj"))

(defn get-program-file
  "Return the program file as a string."
  ([] (get-program-file "main"))
  ([name] (io/file (make-program-path name))))

(defn get-program-content
  "Return the string content of a program file. Nil if file doesn't
   exist (or is empty)."
  ([] (get-program-content "main"))
  ([n] (try (slurp (get-program-file n))
            (catch Exception e))))

(defn set-program
  "Insert the given string into the program file. Program file default
   to 'main' if not specified."
  ([content] (set-program "main" content))
  ([name content] (mkdir-spit (get-program-file name) content)))

(defn load-program
  "Load a given program file. Default to 'main' if no name is given."
  ([] (load-program "main"))
  ([name] (load-file (make-program-path name))))

(defn save-and-load
  "Save and load a program string. Default to 'main' if no name is given."
  ([content] (save-and-load "main" content))
  ([name content]
     (set-program name content)
     (load-program name)))


;; local device configs
(def config-file
  (str path "configs.clj"))

(defn get-configs
  "Get the map configs for the local device."[]
  (let [configs (try (-> config-file slurp read-string)
                     (catch Exception e))]
    (when (map? configs) configs)))

(defn save-configs
  "Save configs for the local device. Should be a map. Return the
  configs." [content]
  {:pre [(map? content)]}
  (mkdir-spit config-file (pr-str content))
  content)

(defn merge-and-save-configs
  "Merge the given map with the config file. Return the resulting map."
  [content]
  (-> (get-configs)
      (merge content)
      save-configs))

(defn delete-configs
  "Delete the configuration file for the local-device."[]
  (try (io/delete-file config-file)
       (catch Exception e)))