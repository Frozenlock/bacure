(ns bacure.coerce.type.primitive
  (:require [bacure.coerce :as c :refer [bacnet->clojure clojure->bacnet]]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.coerce]
            [clj-time.local :as t-local])
  (:import [com.serotonin.bacnet4j.type.primitive

            ;; BitString  ;; Not needed for bacure?
            ;; Boolean    ;; namespace clash... just use the full name            
            CharacterString
            Date
            ;; Double ;; namespace clash... just use the full name
            ObjectIdentifier
            ;; OctetString ;; not needed for bacure?
            
            Primitive
            Real
            SignedInteger
            Time
            Unsigned16
            Unsigned32
            Unsigned8
            UnsignedInteger
            ]))



(defn c-boolean [bool]
  (com.serotonin.bacnet4j.type.primitive.Boolean. bool))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Boolean
  [^com.serotonin.bacnet4j.type.primitive.Boolean o]
  (.booleanValue o))

(defmethod clojure->bacnet :boolean
  [_ value]
  (c-boolean value))

;;;

(defn c-character-string [value]
  (CharacterString. (or value "")))

(defmethod clojure->bacnet :character-string
  [_ value]
  (c-character-string value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.CharacterString
  [^CharacterString o]
  (.toString o))


;;;

(defn c-date [string]
  (if-not string 
    (Date.)
    (let [time-o (t-local/to-local-date-time
                  (tf/parse
                   (tf/formatters :year-month-day) string))]
      (Date. (.toGregorianCalendar time-o)))))

(defmethod clojure->bacnet :date
  [_ value]
  (c-date value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Date
  [^Date o]
  (tf/unparse
   (tf/formatters :year-month-day)
   (t/date-time (+ 1900 (.getYear o))
                            (.getId (.getMonth o))
                            (.getDay o))))

;;;

(defn c-double [number]
  (com.serotonin.bacnet4j.type.primitive.Double. (double (or number 0))))

(defmethod clojure->bacnet :double
  [_ value]
  (c-double value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Double
  [^com.serotonin.bacnet4j.type.primitive.Double o]
  (Double/parseDouble (.toString o))) ;; no 'getDouble' method :-(


;;;


;;; ENUMERATED, but we need it for the object-identifier

(def obj-int-map
  (c/subclass-to-map com.serotonin.bacnet4j.type.enumerated.ObjectType))

(defn c-object-type
  "Type can be either the integer or the keyword"[object-type]
  (com.serotonin.bacnet4j.type.enumerated.ObjectType.
   (if (keyword? object-type)
     (if-let [[_ n] (re-find #"vendor-specific-(\d+)" (name object-type))]
       (read-string n)
       (get obj-int-map object-type))
     (or object-type 0))))

(defmethod clojure->bacnet :object-type
  [_ value]
  (c-object-type value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.enumerated.ObjectType
  [^com.serotonin.bacnet4j.type.enumerated.ObjectType o]
  (c/object-to-keyword o))

;;;


(defn c-object-identifier
  "Make an object identifier."
  ([] (c-object-identifier [0 0]))
  ([[object-type object-instance]]
   (ObjectIdentifier.
    (clojure->bacnet :object-type object-type)
    object-instance)))

(defmethod clojure->bacnet :object-identifier
  [_ value]
  (if (seq value)
    (c-object-identifier value)
    (c-object-identifier)))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.ObjectIdentifier
  [^ObjectIdentifier o]
  [(keyword (bacnet->clojure (.getObjectType o)))
   (.getInstanceNumber o)])


;;;


(defn c-real [value]
  (Real. (float (or value 0))))

(defmethod clojure->bacnet :real
  [_ value]
  (c-real value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Real
  [^Real o]
  (let [value (.floatValue o)]
    (when (== value value) ;; Sometimes the java.lang.float can be NaN (not a number).
      value)))             ;; The equality test can filter them out.

;;;


(defn c-signed [value]
  (SignedInteger. (or value 0)))

(defmethod clojure->bacnet :signed-integer
  [_ value]
  (c-signed value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.SignedInteger
  [^com.serotonin.bacnet4j.type.primitive.SignedInteger o]
  (.intValue o))

;;;


(defn c-time [string]
  (let [time (if string
               (-> (clj-time.format/formatters :hour-minute-second-ms)
                   (clj-time.format/parse string)
                   (t-local/to-local-date-time))
               (t-local/local-now))]
    (com.serotonin.bacnet4j.type.primitive.Time.
     (t/hour time)
     (t/minute time)
     (t/second time)
     (t/milli time))))

(defmethod clojure->bacnet :time
  [_ value]
  (c-time value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Time
  [^com.serotonin.bacnet4j.type.primitive.Time o]
  (.toString o))


;;;

(defn c-unsigned-16 [value]
  (Unsigned16. (or value 0)))

(defmethod clojure->bacnet :unsigned-16
  [_ value]
  (c-unsigned-16 value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Unsigned16
  [^com.serotonin.bacnet4j.type.primitive.Unsigned16 o]
  (Integer/parseInt (.toString o)))


;;;


(defn c-unsigned-32 [value]
  (Unsigned32. (or value 0)))

(defmethod clojure->bacnet :unsigned-32
  [_ value]
  (c-unsigned-32 value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Unsigned32
  [^com.serotonin.bacnet4j.type.primitive.Unsigned32 o]
  (Integer/parseInt (.toString o)))

;;;

(defn c-unsigned-8 [value]
  (Unsigned8. (or value 0)))

(defmethod clojure->bacnet :unsigned-8
  [_ value]
  (c-unsigned-8 value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.Unsigned8
  [^com.serotonin.bacnet4j.type.primitive.Unsigned8 o]
  (Integer/parseInt (.toString o)))

;;;


(defn c-unsigned-Integer [value]
  (UnsignedInteger. (or value 0)))

(defmethod clojure->bacnet :unsigned-integer
  [_ value]
  (c-unsigned-Integer value))

(defmethod bacnet->clojure com.serotonin.bacnet4j.type.primitive.UnsignedInteger
  [^com.serotonin.bacnet4j.type.primitive.UnsignedInteger o]
  (Integer/parseInt (.toString o)))

;;;


(defn c-primitive
  "Given 'something', we take an educated guess as to what this should
  become. (Contrary to everything else in the BACnet world, the
  TimeValue object accepts MANY different types of data. As such, we
  can't just convert it to a bacnet4j equivalent as we usually do.)"
  [something]
  (let [try-fn (fn [coerce-fn value]
                 (try (coerce-fn value)
                      (catch Exception e)))]
    ;; now try to coerce, in the order of 'less likely to be a false
    ;; match'.

    ;; There's probably others (like Double), but we don't have them
    ;; implemented yet.
    (or (try-fn c-object-identifier something)
        (try-fn c-unsigned-integer something)
        ;;(try-fn c-signed something) ;; can't really distinguish from unsigned
        (try-fn c-real something)
        (try-fn c-time something)
        (try-fn c-date something)
        (try-fn c-boolean something)
        (try-fn c-character-string something))))

(defmethod clojure->bacnet :primitive
  [_ value]
  (c-primitive value))

