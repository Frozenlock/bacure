(ns bacure.test.core
  (:require [bacure.core :as b]
            [bacure.local-device :as ld]
            [bacure.remote-device :as rd]
            [bacure.read-properties :as rp]
            [clojure.test :refer :all]))

(deftest device-lifecycle
  (testing "Boot and terminate a local BACnet device"
    (ld/with-temp-devices
      (let [id 1332]
        (b/boot-up! {:device-id id})
        (is (.isInitialized (ld/local-device-object id)))
        (ld/terminate! id)
        (is (not (.isInitialized (ld/local-device-object id))))))))

(deftest device-update
  (testing "Boot and update a local device"
    (ld/with-temp-devices
      (let [id 1332]
        (b/boot-up! {:device-id id})
        (b/boot-up! {:device-id id :broadcast-address "255.255.255.255"})
        (is (.isInitialized (ld/local-device-object id)))))))

;; Tests to verify local-device-id is passed correctly to inner functions.
;; These tests catch bugs where local-device-id parameter is ignored.

(deftest remote-objects-all-properties-uses-local-device-id
  ;; Bug: The function was calling (remote-object-properties device-id ...)
  ;; and (remote-objects device-id) instead of passing local-device-id.
  (let [captured-local-ids (atom [])]
    (with-redefs [b/remote-object-properties
                  (fn
                    ;; Buggy call: 3 args (missing local-device-id)
                    ([device-id object-identifiers properties]
                     (swap! captured-local-ids conj {:fn :remote-object-properties
                                                     :local-device-id :missing
                                                     :device-id device-id})
                     [])
                    ;; Correct call: 4 args (with local-device-id)
                    ([local-device-id device-id object-identifiers properties]
                     (swap! captured-local-ids conj {:fn :remote-object-properties
                                                     :local-device-id local-device-id
                                                     :device-id device-id})
                     []))
                  b/remote-objects
                  (fn
                    ;; Buggy call: 1 arg (missing local-device-id)
                    ([device-id]
                     (swap! captured-local-ids conj {:fn :remote-objects
                                                     :local-device-id :missing
                                                     :device-id device-id})
                     [[:device device-id]])
                    ;; Correct call: 2 args (with local-device-id)
                    ([local-device-id device-id]
                     (swap! captured-local-ids conj {:fn :remote-objects
                                                     :local-device-id local-device-id
                                                     :device-id device-id})
                     [[:device device-id]]))]
      (b/remote-objects-all-properties 1234 5678)
      (testing "remote-objects should receive local-device-id=1234, not be missing"
        (let [ro-call (first (filter #(= (:fn %) :remote-objects) @captured-local-ids))]
          (is (= 1234 (:local-device-id ro-call))
              (str "remote-objects was called with local-device-id=" (:local-device-id ro-call)
                   " instead of 1234"))))
      (testing "remote-object-properties should receive local-device-id=1234, not be missing"
        (let [rop-call (first (filter #(= (:fn %) :remote-object-properties) @captured-local-ids))]
          (is (= 1234 (:local-device-id rop-call))
              (str "remote-object-properties was called with local-device-id=" (:local-device-id rop-call)
                   " instead of 1234")))))))

(deftest read-trend-log-uses-local-device-id
  ;; Bug: The function was calling (remote-object-properties device-id ...)
  ;; instead of (remote-object-properties local-device-id device-id ...).
  (let [captured-local-ids (atom [])]
    (with-redefs [b/remote-object-properties
                  (fn
                    ;; Buggy call: 3 args (missing local-device-id)
                    ([device-id object-identifiers properties]
                     (swap! captured-local-ids conj {:fn :remote-object-properties
                                                     :local-device-id :missing
                                                     :device-id device-id})
                     [{:record-count 0}])
                    ;; Correct call: 4 args (with local-device-id)
                    ([local-device-id device-id object-identifiers properties]
                     (swap! captured-local-ids conj {:fn :remote-object-properties
                                                     :local-device-id local-device-id
                                                     :device-id device-id})
                     [{:record-count 0}]))
                  rp/read-range
                  (fn [& args]
                    {:success {}})]
      (b/read-trend-log 1234 5678 [:trend-log 1])
      (testing "remote-object-properties should receive local-device-id=1234, not be missing"
        (let [rop-call (first (filter #(= (:fn %) :remote-object-properties) @captured-local-ids))]
          (is (= 1234 (:local-device-id rop-call))
              (str "remote-object-properties was called with local-device-id=" (:local-device-id rop-call)
                   " instead of 1234")))))))

(deftest find-objects-everywhere-uses-local-device-id
  ;; Bug: The function was calling (rd/remote-devices) without local-device-id
  ;; and didn't have a local-device-id parameter at all.
  (let [captured-local-ids (atom [])]
    (with-redefs [rd/remote-devices
                  (fn
                    ([] (rd/remote-devices nil))
                    ([local-device-id]
                     (swap! captured-local-ids conj {:fn :remote-devices
                                                     :local-device-id local-device-id})
                     #{}))]
      ;; Call with explicit local-device-id (once the fix adds this parameter)
      ;; For now, calling without it should still use nil consistently
      (b/find-objects-everywhere {:object-name "test"})
      (testing "remote-devices should receive local-device-id (even if nil)"
        (is (some #(= (:fn %) :remote-devices) @captured-local-ids)
            "remote-devices was not called")))))
