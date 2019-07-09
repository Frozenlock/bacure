(ns bacure.util)

(defn configurable-wait
  [{:keys [wait-seconds]
    :or   {wait-seconds 1}
    :as   args}]
  (Thread/sleep (* 1000 wait-seconds)))

(defmacro mapify
  "Given some symbols, construct a map with the symbols as keys, and
  the value of the symbols as the map values. For example:
  (Let [aa 12]
     (mapify aa))
  => {:aa 12}"
  [& symbols]
  `(into {}
         (filter second
                 ~(into []
                        (for [item symbols]
                          [(keyword item) item])))))



;; ================================================================
;; 'defnd' macro
;; ================================================================

(comment
  ;; dummy function to test the `defnd` macro :
  (defnd my-fn
    ([local-device-id] (my-fn local-device-id {:a 12}))
    ([local-device-id {:keys [a] :or {a 1}}] (+ (or local-device-id 1) a 2)))


  ;; emacs clojure-mode configurations
  ;; indentation
  (define-clojure-indent
    (defnd :defn))
  ;; font
  (put 'defnd 'clojure-doc-string-elt 2))

(defn validate-arglists [arglists]
  (every? #(= (first %) 'local-device-id) arglists))

(alter-meta!
 (defmacro defnd
   "Same as `defn`, but will automatically add an n-1 arity,
  where the first argument (local-device-id) can be omited. For
  example, given the following arguments, it will create a new arity :
  [local-device-id remote-device-id] -> [remote-device-id].

  This is intended to make it easier to use the functions at the REPL
  when working with a single local-device."
   [& body]
   `(let [fn-var#   (defn ~@body)
          fn#       (var-get fn-var#)
          meta#     (meta fn-var#)
          arglists# (sort-by count (:arglists meta#))]
      (if (validate-arglists arglists#)
        (-> (concat
             (->> (list 'defn (:name meta#) (:doc meta#)) ;; (defn some-symbol docstring
                  (remove nil?)) ;; remove the docstring if missing
             (for [arg# arglists# ;; arity-1 for all arglists
                   :let [arg-1# (vec (for [i# (range (count (rest arg#)))]
                                       (gensym)))]]
               (list arg-1# (concat (list fn# nil)
                                    arg-1#)))
             ;; add an unchanged version of the last arglist
             (let [arg# (vec (for [i# (range (count (last arglists#)))]
                               (gensym)))]
               (list (list arg# (concat (list fn#) arg#)))))
            (eval)
            (alter-meta! (fn [m#]
                           (assoc m# :arglists (concat (for [a# arglists#]
                                                         (vec (rest a#)))
                                                       [(last arglists#)])))))

        (throw (Exception. "First argument in every arity for 'defnd' must be 'local-device-id'.")))))
 (fn [m] ;; use the same arglists as 'defn'
   (assoc m :arglists (:arglists (meta #'defn)))))
