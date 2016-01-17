(ns bacure.coerce
  (:require [clojure.string :as str])
  (:import [java.util ArrayList List]))

(defmulti bacnet->clojure
  "Recursively convert objects from the bacnet4j library into clojure datastructure." class)

(defmethod bacnet->clojure :default [x]
  (println "No method YET to coerce " (.toString x)" into a clojure structure."))


;; methods for clojure stuff

(defmethod bacnet->clojure clojure.lang.PersistentArrayMap
    [^clojure.lang.PersistentArrayMap o]
  o)

(defmethod bacnet->clojure clojure.lang.PersistentHashMap
  [^clojure.lang.PersistentHashMap o]
  o)

(defmethod bacnet->clojure clojure.lang.PersistentVector
  [^clojure.lang.PersistentVector o]
  o)


(defmethod bacnet->clojure nil [_] nil)


;; methods for java

(defmethod bacnet->clojure java.util.ArrayList
  [^ArrayList o]
  (map bacnet->clojure (seq o)))

(defmethod bacnet->clojure java.lang.String
  [^String o]
  o)

;;;;;;;;;;;;;;


(defmulti clojure->bacnet
  "Transform clojure data values into the given bacnet type.

  Run the function `bacnet-types' to see the available types." 
  (fn [bacnet-type & value-col]
    bacnet-type))

(defn bacnet-types
  "Return a list of the acceptable bacnet types (keywords) for the function
  `clojure->bacnet'." []
  (keys (methods clojure->bacnet)))


;;;;;;;;;;;;;;


;; functions to convert between camel-case and a more clojure-like style

(defn from-camel [string]
  (let [pre-string (subs string 0 1) ;;we want to keep the first
                                     ;;uppercase letter, so we can
                                     ;;reverse the process
        post-string (subs string 1)]
    (str pre-string (-> (str/replace post-string 
                                     #"[A-Z][^A-Z]+" 
                                     #(str \- (str/lower-case %)))
                        (str/replace #"\d+" #(str \- %)) ;; separate digits
                        (str/replace #"([A-Z]+)" "-$1")))))

(defn to-camel [string] (str/replace string #"-." #(str/upper-case (last %))))

(defn object-to-keyword
  "Convert bacnet4j objects to clojure keywords"
  [bacnet-object]
  (let [string (-> (str/replace (.toString bacnet-object)  " " "-")
                   (str/lower-case)
                   (str/replace #"\(|\)|:" ""))]
    (when (seq string)
      (keyword string))))


(defn subclass-to-map
  "Make a map of the subclasses and associate them with their integer
  value."[class]
  (let [fields (.getDeclaredFields class)]
    (->> (for [f fields]
           (try [(-> (.getName f)
                     from-camel
                     object-to-keyword)
                 (.intValue (.get f nil))]
                (catch Exception e)))
         (remove nil?)
         (into {}))))

(defn map-or-num 
  "Will convert the key-or-num into the integer value associated in
  the data-map."
  [data-map key-or-num]
  (if (keyword? key-or-num)
    (if-let [[_ n] (re-find #"unknown-(\d+)" (name key-or-num))]
      (read-string n)
      (get data-map key-or-num))
    key-or-num))


;;; might not be needed

;; (defn construct [klass & args]
;;     (clojure.lang.Reflector/invokeConstructor klass (into-array Object args)))
