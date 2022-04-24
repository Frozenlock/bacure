(ns bacure.coerce
  (:require [clojure.edn :as edn]
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util ArrayList]))

(defmulti bacnet->clojure
  "Recursively convert objects from the bacnet4j library into clojure datastructure." class)

(defmethod bacnet->clojure :default [x]
  (log/warn (str "No method YET to coerce " (.toString x)" into a clojure structure.")))


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

(defmethod bacnet->clojure true [_] true)
(defmethod bacnet->clojure false [_] false)


;; methods for java

(defmethod bacnet->clojure java.util.ArrayList
  [^ArrayList o]
  (map bacnet->clojure (seq o)))

(defmethod bacnet->clojure java.lang.String
  [^String o]
  o)

(defmethod bacnet->clojure java.lang.Boolean
  [^Boolean o]
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

(defn example
  "Generate an example of the expected
  datastructure."
  [object-type-keyword]
  (-> (clojure->bacnet object-type-keyword nil)
      (bacnet->clojure)))


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

(defn class-to-keyword
  [bacnet-class]
  (-> (re-find #"[A-Za-z0-9]*$" (str bacnet-class))
      (from-camel)
      (clojure.string/lower-case)
      (keyword)))

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

(defn key-or-num-to-int
  "Will convert the key-or-num into the integer value associated in
  the data-map. If the keyword is not found in the data-map, check to
  see if it's an `unknown-XX' and extract the number.
  
  Default to '0' if the provided value is not a keyword nor a number.
  
  An error is thrown if the keyword doesn't match anything."
  [data-map key-or-num]
  (if (keyword? key-or-num)
    (or (get data-map key-or-num)
        (let [[_ n] (re-find #"unknown-(\d+)" (name key-or-num))]
          (if n (edn/read-string n)
              (throw (Exception. (str "Keyword doesn't match anything expected : " key-or-num))))))
    (or key-or-num 0))) ;; default to 0 if nothing is provided

(defn int-to-keyword*
  "Convert the integer to its associated keyword in the data-map. If
  no match is found, generate an `unknown-XX' keyword, where XX is the
  given integer.

  TAKES THE INVERTED DATAMAP"
  [inverted-data-map integer]
  (or (get inverted-data-map integer)
      (keyword (str "unknown-" integer))))

(defn int-to-keyword
  "Convert the integer to its associated keyword in the data-map. If
  no match is found, generate an `unknown-XX' keyword, where XX is the
  given integer."
  [data-map integer]
  (int-to-keyword* (cset/map-invert data-map) integer))

(defn bean-map
  "Create a clojure map using `bean' and remove unwanted info"
  [java-object]
  (let [m (dissoc (bean java-object) :class :value)]
    (into {}
          (for [item m]
            [(keyword (from-camel (name (key item))))
             (val item)]))))


(defmacro def-subclass-map [bacnet-class subclass-map]
  `(def ~(-> (re-find #"[A-Za-z0-9]*$" (str bacnet-class))
             (from-camel)
             (clojure.string/lower-case)
             (str "-map")
             (symbol))
     "Map of keywords and associated integer values."
     ~subclass-map))

(defmacro enumerated-converter
  "Generate the defmethods for `bacnet->clojure' and `clojure->bacnet'
  for bacnet enumerated types. 
  
  Also define a <class-name>-map which is a map of keywords and
  integer values."
  [bacnet-class]
  `(let [subclass-map# (subclass-to-map ~bacnet-class)
         inverted-subclass-map# (cset/map-invert subclass-map#)
         conversion-key# (-> (re-find #"[A-Za-z0-9]*$" (str (quote ~bacnet-class)))
                             (from-camel)
                             (clojure.string/lower-case)
                             (keyword))]
     
     (list (defmethod clojure->bacnet conversion-key#
             [_# key-or-number#]
             (. ~bacnet-class (forId
                               (key-or-num-to-int subclass-map# key-or-number#))))

           (defmethod bacnet->clojure ~bacnet-class
             [value#]
             (->> (.intValue value#)
                  (int-to-keyword* inverted-subclass-map#)))

           (def-subclass-map ~bacnet-class subclass-map#))))

;;================================================================







;; (defn encode-properties-values
;;   "Take an object-map (a map of properties) and encode the properties
;;   into their native java object. For example, a clojure number \"1\"
;;   might be encoded into a real, or into an unisgned integer, depending
;;   on the object." [obj-map]
;;   (let [o-t (get-object-type obj-map)]
;;     (into {}
;;           (for [[property value] obj-map]
;;             (try 
;;               [property (encode-property o-t property value)]
;;               (catch Exception e (println (str "Could not encode *** " property 
;;                                                " ***. Might not be implemented yet."))))))))
       
;; (defn encode-properties
;;   "Encode an object map into a sequence of bacnet4j property-values.
;;   Remove-keys can be used to remove read-only properties before
;;   sending a command." [obj-map & remove-keys]
;;   (let [encoded-values-map (apply dissoc (cons (encode-properties-values obj-map) remove-keys))]
;;     (c-array #(c-property-value (c-property-identifier (key %)) (val %)) encoded-values-map)))
