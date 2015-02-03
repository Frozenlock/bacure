(ns bacure.read-properties-cached
  (:require [clojure.core.cache :as cache]
            [bacure.read-properties :as rp]
            [clojure.set :as s]))

;; Some caching capabilities to avoid re-querying the network for
;; nothing.

(def cache-ttl
  (atom (* 1000 60 2)))  ; basic caching of 2 minutes

(def remote-properties-cache
  (atom (cache/ttl-cache-factory {} :ttl @cache-ttl)))

(defn set-cache-ttl!
  "Set the time-to-live (in millis) for every value in the cache. Will
  clear all values currently cached."
  [ttl]
  (reset! cache-ttl ttl)
  (reset! remote-properties-cache
          (cache/ttl-cache-factory {} :ttl ttl)))

(defn store!
  "Store the property value into the cache."
  [device-id prop-ref value]
  (swap! remote-properties-cache assoc [device-id prop-ref]
         {:object-identifier (first prop-ref)
          (last prop-ref) value}))


(defn get-cached-prop
  "Return the cached property, or nil if not found."
  [device-id prop-ref]
  (cache/lookup @remote-properties-cache [device-id prop-ref]))

(comment
  (store! 10222 [[:analog-input 0] :present-value] 10.22)
  (get-cached-prop 10222 [[:analog-input 0] :present-value])
  )


(defn merge-by-oid [prop-coll]
  (->> (group-by :object-identifier prop-coll)
       (vals)
       (map (partial apply merge))))


(defn read-properties-cached
  "Retrieve remote objects properties and cache them for a given
  amount of time.
  
  CANNOT return cached data for :all :required and :optional, but will
  cache the properties returned by those reads.

  As for the normal read-properties, format for
  object-property-references should be:

   [ [[:analog-input 0] :description :object-name]   <--- multiple properties
     [[:device 1234] [:object-list 0]                <--- array access
     [[:analog-ouput 1] :present-value]  ...]"
  
  [device-id object-property-references]
  ;; first we expand the prop-refs for easy storing and retrieval.
  (let [expanded-prop-refs (rp/expand-obj-prop-ref object-property-references)
        ;; gather up the cached properties
        cached-props-with-values (for [prop-ref expanded-prop-refs
                                       :let [v (get-cached-prop device-id prop-ref)]
                                       :when v]
                                   [prop-ref v])
        cached-props (map first cached-props-with-values)
        non-cached-props (s/difference (set expanded-prop-refs) (set cached-props))
        cached-results (map last cached-props-with-values)
        ;; read the non-cached remote properties
        new-reads (rp/read-properties device-id non-cached-props)]
    ;; cache all the new remote reads
    (doseq [properties-per-object new-reads]
      (let [oid (:object-identifier properties-per-object)]
        (doseq [[property value] (dissoc properties-per-object :object-identifier)]
          (store! device-id [oid property] value))))
    ;; merge the cached and new data into a single result
    (-> (concat cached-results new-reads)
        (merge-by-oid))))

    

