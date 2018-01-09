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
