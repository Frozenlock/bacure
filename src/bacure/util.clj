(ns bacure.util)

(defn private-field
  "Let's break some encapsulation. Courtesy of
  https://gist.github.com/sunng87/13700d3356d5514d35ad"
  [obj field-name-string]

  (let [m (.. obj getClass (getDeclaredField field-name-string))]
    (. m (setAccessible true))
    (. m (get obj))))
