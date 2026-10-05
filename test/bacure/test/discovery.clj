(ns bacure.test.discovery
  "Regression tests for the I-Am / extended-information path.

  Background: on a network where a third-party supervisor sends a
  global Who-Is every minute, every device answers with an I-Am every
  minute. Each I-Am must NOT cost us network requests for devices we
  already know."
  (:require [bacure.core :as b]
            [bacure.events :as events]
            [bacure.local-device :as ld]
            [bacure.read-properties :as rp]
            [bacure.remote-device :as rd]
            [bacure.services :as services]
            [bacure.util :as util]
            [clojure.test :refer :all]
            [taoensso.timbre :as timbre])
  (:import [com.serotonin.bacnet4j RemoteDevice]
           [com.serotonin.bacnet4j.service.confirmed ReadPropertyMultipleRequest]
           [com.serotonin.bacnet4j.type.constructed Address]
           [com.serotonin.bacnet4j.type.enumerated PropertyIdentifier]
           [com.serotonin.bacnet4j.type.primitive UnsignedInteger]
           [java.util.concurrent CyclicBarrier]))

(defn- with-auto-fetch!
  "Register the same I-Am listener that `bacure.core/boot-up!` installs."
  [ld-id]
  (ld/add-listener! ld-id (rd/IAm-received-auto-fetch-extended-information ld-id)))

(defn- counting-requests
  "Return [counter wrapped-fn] where wrapped-fn calls the original
  `send-request-promise` and counts every call."
  []
  (let [n (atom 0)
        original services/send-request-promise]
    [n (fn [& args] (swap! n inc) (apply original args))]))

;;; ----------------------------------------------------------------
;;; Production wiring
;;; ----------------------------------------------------------------

(deftest boot-up-discovers-extended-information-on-its-own
  (testing "Devices booted with `boot-up!` learn each other's identity without explicit calls"
    (ld/with-temp-devices
      ;; `boot-up!` starts a background `discover-network` that can
      ;; outlive this test; use device IDs no other test uses so it
      ;; can't act on another test's devices.
      (let [port 47555
            [a b] [61 62]
            boot! (fn [id]
                    (b/boot-up! {:device-id         id
                                 :port              port
                                 :local-address     (str "127.0.0." id)
                                 :broadcast-address "127.0.0.255"}))]
        (boot! a)
        (boot! b)
        (ld/register-as-foreign-device a (str "127.0.0." b) port 60)
        (ld/register-as-foreign-device b (str "127.0.0." a) port 60)
        (ld/i-am-broadcast! a)
        (ld/i-am-broadcast! b)
        ;; Only look at the cache: never ask explicitly. (The cache is
        ;; non-nil as soon as the services are known, so wait for the
        ;; name, which comes with the second request.)
        (util/wait-while #(not (and (:object-name (rd/cached-extended-information a b))
                                    (:object-name (rd/cached-extended-information b a))))
                         5000)
        (is (= (str "Bacure device " b) (:object-name (rd/cached-extended-information a b))))
        (is (= (str "Bacure device " a) (:object-name (rd/cached-extended-information b a))))))))

;;; ----------------------------------------------------------------
;;; Known devices must stay known
;;; ----------------------------------------------------------------

(deftest i-am-keeps-extended-information
  (testing "An I-Am from a device we already know must not erase its identity"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
        (is (:object-name (rd/extended-information ld-id rd-id)))
        ;; Disable any automatic re-fetch so we observe the raw effect
        ;; of the I-Am on the cache.
        (with-redefs [rd/retrieve-extended-information! (constantly nil)]
          (ld/i-am-broadcast! rd-id)
          (Thread/sleep 300)
          (is (:object-name (rd/cached-extended-information ld-id rd-id))
              "cached extended information was lost after an I-Am"))))))

(deftest i-am-from-known-device-costs-no-request
  (testing "The auto-fetch listener must not query a device whose identity is cached"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
        (with-auto-fetch! ld-id)
        (is (:object-name (rd/extended-information ld-id rd-id)))
        (let [[n counting] (counting-requests)]
          (with-redefs [services/send-request-promise counting]
            ;; Simulate a supervisor's Who-Is: several I-Am in a row.
            (dotimes [_ 3]
              (ld/i-am-broadcast! rd-id)
              (Thread/sleep 200))
            (Thread/sleep 500)
            (is (= 0 @n)
                (str @n " request(s) sent for an already known device"))))))))

(deftest i-am-updates-address-but-keeps-identity
  (testing "A device that moved keeps its identity and gets its new address"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
        (is (:object-name (rd/extended-information ld-id rd-id)))
        (let [ldo         (ld/local-device-object ld-id)
              new-address (Address. (byte-array (map unchecked-byte [127 0 0 42 0xBA 0xC0])))
              moved       (doto (RemoteDevice. ldo rd-id new-address)
                            (.setDeviceProperty PropertyIdentifier/vendorIdentifier (UnsignedInteger. 999)))]
          (with-redefs [rd/retrieve-extended-information! (constantly nil)]
            (.iAmReceived (events/unconfirmed-event-listener ld-id) moved)
            (is (= new-address (.getAddress (rd/rd ld-id rd-id)))
                "address should follow the latest I-Am")
            (is (= 999 (.intValue (.getDeviceProperty (rd/rd ld-id rd-id) PropertyIdentifier/vendorIdentifier)))
                "properties advertised in the I-Am should be updated")
            (is (:object-name (rd/cached-extended-information ld-id rd-id))
                "identity should survive an address change")))))))

(deftest failed-identity-fetch-backs-off
  (testing "A device that never answers identity reads is not asked again on every I-Am"
    (ld/with-temp-devices
      (let [ld-id      1
            _          (ld/new-local-device! {:device-id         ld-id
                                              :port              47555
                                              :local-address     "127.0.0.1"
                                              :broadcast-address "127.0.0.255"
                                              :extended-information-retry-ms     400
                                              :extended-information-max-retry-ms 800})
            _          (ld/initialize! ld-id)
            ldo        (ld/local-device-object ld-id)
            silent-id  200001
            silent     (RemoteDevice. ldo silent-id
                                      (Address. (byte-array (map unchecked-byte [127 0 0 99 0xBA 0xC0]))))
            cache-l    (events/unconfirmed-event-listener ld-id)
            fetch-l    (rd/IAm-received-auto-fetch-extended-information ld-id)
            n          (atom 0)
            ;; Same order as bacnet4j: cache listener first, then auto-fetch.
            i-am!      (fn [] (.iAmReceived cache-l silent) (.iAmReceived fetch-l silent))]
        (with-redefs [services/send-request-promise
                      (fn [& _] (swap! n inc) {:timeout "no answer"})]
          (dotimes [_ 5] (i-am!))
          (is (= 1 @n) (str @n " fetch attempts within the back-off window"))
          ;; The back-off only applies to what we do on our own: an
          ;; explicit request must always be honored. (It throws on
          ;; timeout, as it always did.)
          (is (thrown? Exception (rd/extended-information ld-id silent-id)))
          (is (= 2 @n) "an explicit call must try even during the back-off window")
          ;; Second failure in a row: the window doubles (800 ms).
          (Thread/sleep 500)
          (i-am!)
          (is (= 2 @n) "the back-off window should grow after consecutive failures")
          (Thread/sleep 400)
          (i-am!)
          (is (= 3 @n) "should retry once the back-off window has elapsed")
          ;; Third failure: 3 x 400 would be 1200 ms, but the maximum is 800 ms.
          (Thread/sleep 900)
          (i-am!)
          (is (= 4 @n) "the back-off window shouldn't exceed the maximum"))))))

;;; ----------------------------------------------------------------
;;; Concurrency
;;; ----------------------------------------------------------------

(deftest concurrent-i-am-do-not-lose-devices
  (testing "Simultaneous I-Am from many devices must all end up in the cache"
    (ld/with-temp-devices
      (let [[ld-id] (rd/local-registered-test-devices! 1)
            ldo      (ld/local-device-object ld-id)
            listener (events/unconfirmed-event-listener ld-id)
            ids      (range 100000 100040)
            rounds   5
            lost     (atom [])]
        (dotimes [round rounds]
          (events/clear-cached-remote-devices! ld-id)
          (let [barrier (CyclicBarrier. (count ids))
                threads (for [id ids]
                          (Thread. (fn []
                                     (.await barrier)
                                     (.iAmReceived listener (RemoteDevice. ldo id)))))]
            (doseq [t threads] (.start t))
            (doseq [t threads] (.join t)))
          (let [missing (remove (rd/remote-devices ld-id) ids)]
            (when (seq missing)
              (swap! lost conj {:round round :missing (vec missing)}))))
        (is (empty? @lost)
            (str "devices lost to a race in " (count @lost) "/" rounds " rounds: " (pr-str @lost)))))))

(deftest concurrent-extended-information-callers-all-get-data
  (testing "A second caller arriving mid-fetch must get the data, not nil"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original-read rp/read-individually
            [n counting]  (counting-requests)]
        (is (nil? (rd/cached-extended-information ld-id rd-id)))
        ;; Widen the fetch window so the second caller reliably
        ;; arrives while the first one is still fetching.
        (with-redefs [rp/read-individually (fn [& args]
                                             (Thread/sleep 300)
                                             (apply original-read args))
                      services/send-request-promise counting]
          (let [first-caller  (future (rd/extended-information ld-id rd-id))
                _             (Thread/sleep 50)
                second-caller (future (rd/extended-information ld-id rd-id))]
            (is (:object-name (deref first-caller 5000 :timeout)))
            (is (:object-name (deref second-caller 5000 :timeout))
                "second caller got nothing because a fetch was already in progress")
            ;; One fetch = 1 ReadProperty (services) + 1 ReadPropertyMultiple.
            (is (= 2 @n) (str @n " requests: the second caller should share the first fetch"))))))))

(deftest waiters-are-released-when-the-fetch-fails
  (testing "A caller waiting on someone else's fetch gets nil promptly if that fetch times out"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
        (with-redefs [services/send-request-promise (fn [& _]
                                                      (Thread/sleep 300)
                                                      {:timeout "no answer"})]
          (let [owner  (future (try (rd/extended-information ld-id rd-id)
                                    (catch Exception e ::threw)))
                _      (Thread/sleep 50)
                waiter (future (rd/extended-information ld-id rd-id))]
            (is (= ::threw (deref owner 5000 :timeout)) "the owner sees the timeout")
            (is (nil? (deref waiter 2000 :hung)) "the waiter must be released with nil")))))))

;;; ----------------------------------------------------------------
;;; Auto-fetch filter
;;; ----------------------------------------------------------------

(deftest auto-fetch-filter-restricts-what-we-fetch-on-our-own
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
          [n counting]  (counting-requests)]
      (with-auto-fetch! ld-id)
      (with-redefs [services/send-request-promise counting]
        (testing "a rejected device is still listed, but not asked anything"
          (rd/set-auto-fetch-filter! ld-id (constantly false))
          (dotimes [_ 3]
            (ld/i-am-broadcast! rd-id)
            (Thread/sleep 100))
          (Thread/sleep 300)
          (is (= 0 @n))
          (is (contains? (rd/remote-devices ld-id) rd-id))
          (is (nil? (rd/cached-extended-information ld-id rd-id))))

        (testing "the filter receives the device ID and can be replaced at any time"
          (rd/set-auto-fetch-filter! ld-id #{rd-id})
          (ld/i-am-broadcast! rd-id)
          (util/wait-while #(nil? (:object-name (rd/cached-extended-information ld-id rd-id))) 2000)
          (is (= "Bacure device 2" (:object-name (rd/cached-extended-information ld-id rd-id)))))))))

(deftest auto-fetch-filter-does-not-restrict-explicit-calls
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)]
      (rd/set-auto-fetch-filter! ld-id (constantly false))
      (is (= "Bacure device 2" (:object-name (rd/extended-information ld-id rd-id)))))))

(deftest auto-fetch-filter-lifecycle
  (ld/with-temp-devices
    (let [f     (constantly false)
          boot! (fn [id & runtime-options]
                  (apply b/boot-up! {:device-id         id
                                     :port              47555
                                     :local-address     (str "127.0.0." id)
                                     :broadcast-address "127.0.0.255"}
                         runtime-options))
          ;; (IDs not used by other tests, see `boot-up-discovers-...`)
          [a b] [63 64]]
      (testing "given to boot-up!, the filter is in place before the discovery"
        (boot! a {:auto-fetch-filter f})
        (boot! b)
        (is (= f (rd/auto-fetch-filter a)))
        (is (nil? (rd/auto-fetch-filter b)))
        (ld/register-as-foreign-device a (str "127.0.0." b) 47555 60)
        (ld/register-as-foreign-device b (str "127.0.0." a) 47555 60)
        (ld/i-am-broadcast! a)
        (ld/i-am-broadcast! b)
        ;; b isn't filtered: once it has the name of a, a had the
        ;; same opportunity to fetch the name of b.
        (util/wait-while #(nil? (:object-name (rd/cached-extended-information b a))) 5000)
        (Thread/sleep 300)
        (is (contains? (rd/remote-devices a) b))
        (is (nil? (rd/cached-extended-information a b))))

      (testing "it is not part of the saved configs"
        (let [backup (ld/local-device-backup a)]
          (is (not (contains? backup :runtime-options)))
          (is (not (contains? backup :auto-fetch-filter)))))

      (testing "it survives a reset and a boot-up! without runtime options"
        (ld/reset-local-device! a)
        (is (= f (rd/auto-fetch-filter a)))
        (boot! a)
        (is (= f (rd/auto-fetch-filter a))))

      (testing "it can be removed"
        (boot! a {:auto-fetch-filter nil})
        (is (nil? (rd/auto-fetch-filter a)))))))

;;; ----------------------------------------------------------------
;;; Partial information
;;; ----------------------------------------------------------------

(deftest partial-extended-information-is-completed
  (testing "Services known but name missing: ask again, and only for what's missing"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original  services/send-request-promise
            fail-rpm? (atom true)
            n         (atom 0)]
        (timbre/with-level :fatal
          (with-redefs [services/send-request-promise
                        (fn [& [_ _ request :as args]]
                          (swap! n inc)
                          (if (and @fail-rpm? (instance? ReadPropertyMultipleRequest request))
                            {:timeout "no answer"}
                            (apply original args)))]
            (testing "first attempt: the device stops answering after the services"
              (let [info (rd/extended-information ld-id rd-id)]
                (is (:protocol-services-supported info) "what was learned should be returned")
                (is (nil? (:object-name info))))
              (is (= (rd/extended-information ld-id rd-id)
                     (rd/cached-extended-information ld-id rd-id))))

            (testing "still failing: return what we have, after trying only the missing part"
              (reset! n 0)
              (let [info (rd/extended-information ld-id rd-id)]
                (is (:protocol-services-supported info))
                (is (nil? (:object-name info))))
              (is (= 1 @n) "the services shouldn't be read again"))

            (testing "the device answers again: the information is completed"
              (reset! fail-rpm? false)
              (reset! n 0)
              (is (= "Bacure device 2" (:object-name (rd/extended-information ld-id rd-id))))
              (is (= 1 @n)))

            (testing "complete: nothing more to ask"
              (reset! n 0)
              (is (:object-name (rd/extended-information ld-id rd-id)))
              (is (= 0 @n)))

            (testing "unless we explicitly ask for a refresh"
              (reset! n 0)
              (is (= "Bacure device 2" (:object-name (rd/retrieve-extended-information! ld-id rd-id))))
              ;; services + the other properties
              (is (= 2 @n)))))))))

(deftest property-error-during-fetch
  (testing "A property answered with an error: keep the rest, and later ask only for what's missing"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original  rp/read-properties
            fail?     (atom true)
            requested (atom [])]
        (with-auto-fetch! ld-id)
        (with-redefs [rp/read-properties
                      (fn [local-device-id device-id opr]
                        (swap! requested conj opr)
                        (cond->> (original local-device-id device-id opr)
                          @fail? (map #(if (:object-name %)
                                         (assoc % :object-name {:error {:error-class :property
                                                                       :error-code  :unknown-property}})
                                         %))))]
          (let [info (rd/extended-information ld-id rd-id)]
            (is (:protocol-services-supported info))
            (is (:protocol-version info))
            (is (nil? (:object-name info)) "an error isn't a name"))

          (testing "it counts as a failure: no automatic retry right away"
            (reset! requested [])
            (ld/i-am-broadcast! rd-id)
            (Thread/sleep 300)
            (is (empty? @requested)))

          (testing "an explicit call asks only for the missing property"
            (reset! fail? false)
            (reset! requested [])
            (is (= "Bacure device 2" (:object-name (rd/extended-information ld-id rd-id))))
            (is (= [[[[:device rd-id] :object-name]]] @requested))))))))

(deftest stale-fetch-is-not-joined-after-the-device-was-replaced
  (testing "A fetch in flight for an object no longer in the cache must not be shared"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original services/send-request-promise
            n        (atom 0)
            gate-a   (promise)]
        (timbre/with-level :fatal
          (with-redefs [services/send-request-promise
                        (fn [& args]
                          (if (= 1 (swap! n inc))
                            (do (deref gate-a 10000 nil) {:timeout "no answer"})
                            (apply original args)))]
            (let [fetch-a (future (rd/extended-information ld-id rd-id))]
              (util/wait-while #(< @n 1) 2000)
              ;; The cache is cleared and the device rediscovered: a new object.
              (events/clear-cached-remote-devices! ld-id)
              (ld/i-am-broadcast! rd-id)
              (util/wait-while #(nil? (rd/rd ld-id rd-id)) 2000)
              ;; A new caller gets its own fetch, without waiting for A.
              (let [fetch-b (future (rd/extended-information ld-id rd-id))]
                (is (= "Bacure device 2" (:object-name (deref fetch-b 3000 :hung)))))
              (deliver gate-a true)
              (testing "the stale fetch failed, but its caller gets what we now know"
                (is (= "Bacure device 2" (:object-name (deref fetch-a 2000 :hung))))))))))))

(defn- replace-remote-device!
  "Clear the cache and wait for the remote device to be rediscovered:
  it is then represented by a new object, without any information."
  [ld-id rd-id]
  (events/clear-cached-remote-devices! ld-id)
  (ld/i-am-broadcast! rd-id)
  (util/wait-while #(nil? (rd/rd ld-id rd-id)) 2000))

(deftest stale-fetch-failure-is-not-held-against-the-new-object
  (ld/with-temp-devices
    (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
          original services/send-request-promise
          n        (atom 0)
          gate-a   (promise)]
      (timbre/with-level :fatal
        (with-redefs [services/send-request-promise
                      (fn [& args]
                        (if (= 1 (swap! n inc))
                          (do (deref gate-a 10000 nil) {:timeout "no answer"})
                          (apply original args)))]
          (let [fetch-a (future (try (rd/extended-information ld-id rd-id)
                                     (catch Exception e ::threw)))]
            (util/wait-while #(< @n 1) 2000)
            (replace-remote-device! ld-id rd-id)
            (deliver gate-a true)
            (deref fetch-a 2000 :hung)
            (is (nil? (rd/cached-extended-information ld-id rd-id)))
            ;; If the failure had been recorded for the new object, this
            ;; I-Am would be ignored (back-off).
            (with-auto-fetch! ld-id)
            (ld/i-am-broadcast! rd-id)
            (util/wait-while #(nil? (:object-name (rd/cached-extended-information ld-id rd-id))) 2000)
            (is (= "Bacure device 2" (:object-name (rd/cached-extended-information ld-id rd-id))))))))))

(deftest stale-fetch-success-is-not-published
  (testing "What a fetch learned about a replaced object isn't handed out as the device's information"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original services/send-request-promise
            n        (atom 0)
            gate-a   (promise)]
        (with-redefs [services/send-request-promise
                      (fn [& args]
                        (when (= 1 (swap! n inc))
                          (deref gate-a 10000 nil))
                        (apply original args))]
          (let [fetch-a (future (rd/extended-information ld-id rd-id))]
            (util/wait-while #(< @n 1) 2000)
            (replace-remote-device! ld-id rd-id)
            (deliver gate-a true)
            (is (nil? (deref fetch-a 3000 :hung))
                "the caller gets what we know about the current object: nothing yet")
            (is (nil? (rd/cached-extended-information ld-id rd-id)))
            (is (= "Bacure device 2" (:object-name (rd/extended-information ld-id rd-id)))
                "and the next call fetches it properly")))))))

(deftest fetch-claim-decisions
  ;; Pure functions: every interleaving can be tested without a network.
  (let [claim-fetch @#'rd/claim-fetch
        fetch-role  @#'rd/fetch-role
        fetch-k     :bacure.remote-device/ext-info-fetch
        failures-k  :bacure.remote-device/ext-info-failures
        id 10
        d1 (Object.)
        d2 (Object.)
        claim-d1  {:promise (promise) :device d1}
        new-claim {:promise (promise) :device d1}
        configs   {:extended-information-retry-ms 1000 :extended-information-max-retry-ms 3000}
        state     (fn [cached-dev & {:as more}]
                    (merge {:remote-devices {id cached-dev} :init-configs configs} more))
        role      (fn [s automatic? now]
                    (fetch-role (claim-fetch s id new-claim automatic? now) id d1 new-claim))]
    (testing "free: we take the claim"
      (is (= :owner (role (state d1) false 0))))
    (testing "a fetch is in flight for the same object: wait for it"
      (is (= :waiter (role (state d1 fetch-k {id claim-d1}) false 0))))
    (testing "the object was replaced: start over, even if its old fetch is still in flight"
      (is (= :replaced (role (state d2) false 0)))
      (is (= :replaced (role (state d2 fetch-k {id claim-d1}) false 0)))
      (is (= :replaced (role (state nil) false 0)))
      (is (= :replaced (role nil false 0))))
    (testing "the claim of a fetch for a replaced object doesn't block the new one"
      (let [new-claim-d2 {:promise (promise) :device d2}
            s (claim-fetch (state d2 fetch-k {id claim-d1}) id new-claim-d2 false 0)]
        (is (= :owner (fetch-role s id d2 new-claim-d2)))))
    (testing "back-off only restricts automatic fetches, and grows up to the maximum"
      (let [failed (fn [n] (state d1 failures-k {id {:at 0 :count n}}))]
        (is (= :not-allowed (role (failed 1) true 999)))
        (is (= :owner       (role (failed 1) true 1000)))
        (is (= :owner       (role (failed 1) false 1)))
        (is (= :not-allowed (role (failed 2) true 1999)))
        (is (= :owner       (role (failed 2) true 2000)))
        (is (= :not-allowed (role (failed 9) true 2999)))
        (is (= :owner       (role (failed 9) true 3000)))))))

;;; ----------------------------------------------------------------
;;; Local device reset
;;; ----------------------------------------------------------------

(deftest fetch-started-before-a-reset-does-not-disturb-the-new-one
  (testing "A fetch that outlives a local device reset must not release the claim of a newer fetch"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original services/send-request-promise
            n        (atom 0)
            gate-a   (promise)
            gate-b   (promise)]
        (timbre/with-level :fatal
          (with-redefs [services/send-request-promise
                        (fn [& args]
                          (case (swap! n inc)
                            1 (do (deref gate-a 10000 nil) {:timeout "no answer"})
                            2 (do (deref gate-b 10000 nil) (apply original args))
                            (apply original args)))]
            (let [fetch-a (future (try (rd/extended-information ld-id rd-id)
                                       (catch Exception e ::threw)))]
              (util/wait-while #(< @n 1) 2000)
              ;; Reset the local device while fetch A is waiting.
              (rd/reset-registered-test-device! ld-id rd-id)
              (let [fetch-b (future (rd/extended-information ld-id rd-id))]
                (util/wait-while #(< @n 2) 2000)
                ;; Fetch A fails now, while B is in flight. Its object was
                ;; replaced: instead of its error, it returns what we know
                ;; about the current one (nothing yet).
                (deliver gate-a true)
                (is (nil? (deref fetch-a 2000 :hung)))
                ;; A third caller must join B instead of starting its own fetch.
                (let [fetch-c (future (rd/extended-information ld-id rd-id))]
                  (Thread/sleep 200)
                  (is (= 2 @n) "a third caller started its own fetch: B's claim was released by A")
                  (deliver gate-b true)
                  (is (= "Bacure device 2" (:object-name (deref fetch-b 5000 :hung))))
                  (is (= "Bacure device 2" (:object-name (deref fetch-c 5000 :hung)))))))))))))

;;; ----------------------------------------------------------------
;;; Read-property-multiple fallbacks
;;; ----------------------------------------------------------------

(defn- run-bounded
  "Run f in a future; return its value, the Throwable it threw, or
  ::hung after timeout-ms."
  [timeout-ms f]
  (let [fut (future (try (f) (catch Throwable t t)))
        ret (deref fut timeout-ms ::hung)]
    (when (= ret ::hung) (future-cancel fut))
    ret))

(deftest rpm-rejected-as-unrecognized-service-terminates
  (testing "A device that rejects RPM on a single property must not cause endless retries"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original services/send-request-promise
            n (atom 0)]
        (rd/extended-information ld-id rd-id)
        (timbre/with-level :fatal
          (testing "device rejecting everything"
            (with-redefs [services/send-request-promise
                          (fn [& _]
                            (swap! n inc)
                            {:reject {:reject-reason :unrecognized-service}})]
              (let [ret (run-bounded 10000
                                     #(rp/read-property-multiple ld-id rd-id
                                                                 [[[:device rd-id] :object-name]]))]
                (is (not= ::hung ret) "never returned")
                (is (instance? Exception ret) "the read can't succeed: it should throw, as a plain read-property does")
                (is (= "APDU abort" (some-> ret .getMessage)))
                ;; 1 rejected RPM + 1 rejected read-property
                (is (= 2 @n) (str @n " requests sent for a single property")))))
          (testing "device rejecting only RPM answers through plain ReadProperty"
            (reset! n 0)
            (with-redefs [services/send-request-promise
                          (fn [& [_ _ request :as args]]
                            (swap! n inc)
                            (if (instance? ReadPropertyMultipleRequest request)
                              {:reject {:reject-reason :unrecognized-service}}
                              (apply original args)))]
              (let [ret (run-bounded 10000
                                     #(rp/read-property-multiple ld-id rd-id
                                                                 [[[:device rd-id] :object-name :protocol-version]]))]
                (is (= "Bacure device 2" (:object-name (first ret))))
                (is (:protocol-version (first ret)))
                ;; 1 rejected RPM + 2 individual reads
                (is (= 3 @n) (str @n " requests"))))))))))

(deftest rpm-too-big-on-single-object-multiple-properties-falls-back
  (testing "A size-related abort on [object p1 p2 p3] must fall back, not crash"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original services/send-request-promise]
        (rd/extended-information ld-id rd-id)
        (timbre/with-level :fatal
          (with-redefs [services/send-request-promise
                        (fn [& [_ _ request :as args]]
                          (if (instance? ReadPropertyMultipleRequest request)
                            {:abort {:abort-reason :buffer-overflow}}
                            (apply original args)))]
            (let [ret (run-bounded 10000
                                   #(rp/read-property-multiple
                                     ld-id rd-id
                                     [[[:device rd-id] :object-name :protocol-version]]))]
              (is (not (instance? Throwable ret))
                  (when (instance? Throwable ret)
                    (str "threw " (.getName (class ret)) ": " (.getMessage ^Throwable ret))))
              (is (not= ::hung ret) "never returned")
              (is (= "Bacure device 2" (:object-name (first ret)))
                  "should still return the properties by reading them another way"))))))))

(deftest rpm-too-big-with-special-identifier-falls-back
  (testing "A size-related abort on [object :all] is retried with the actual properties"
    (ld/with-temp-devices
      (let [[ld-id rd-id] (rd/local-registered-test-devices! 2)
            original rp/read-property-multiple*
            special? #{:all :required :optional}]
        (rd/extended-information ld-id rd-id)
        (timbre/with-level :fatal
          ;; A device with a small APDU: can't answer special
          ;; identifiers nor more than 4 properties at once.
          (with-redefs [rp/read-property-multiple*
                        (fn [local-device-id device-id opr]
                          (let [props (mapcat rest opr)]
                            (if (or (some special? props) (> (count props) 4))
                              {:abort {:abort-reason :buffer-overflow}}
                              (original local-device-id device-id opr))))]
            (doseq [special [:all :required]]
              (testing special
                (let [ret (run-bounded 30000
                                       #(doall (rp/read-property-multiple ld-id rd-id [[[:device rd-id] special]])))]
                  (is (not (instance? Throwable ret))
                      (when (instance? Throwable ret)
                        (str "threw " (.getName (class ret)) ": " (.getMessage ^Throwable ret))))
                  (is (not= ::hung ret) "never returned")
                  (is (= "Bacure device 2" (:object-name (first ret))))
                  (is (= "HVAC.IO" (:vendor-name (first ret)))))))))))))

;;; ----------------------------------------------------------------
;;; Configuration
;;; ----------------------------------------------------------------

(deftest maybe-register-as-foreign-device-uses-local-device-id
  (testing "The registration is done by the requested local device, not the default one"
    (ld/with-temp-devices
      (ld/new-local-device! {:device-id 1 :port 47555 :local-address "127.0.0.1"
                             :broadcast-address "127.0.0.255"})
      (ld/new-local-device! {:device-id 2 :port 47555 :local-address "127.0.0.2"
                             :broadcast-address "127.0.0.255"
                             :foreign-device-target {:host "127.0.0.1" :port 47555}})
      (let [calls (atom [])]
        (with-redefs [ld/register-as-foreign-device (fn [& args] (swap! calls conj (vec args)))]
          (ld/maybe-register-as-foreign-device! 2))
        (is (= [[2 "127.0.0.1" 47555 3600]] @calls))))))

(defn- transport-of [ld-id]
  (let [ldo   (ld/local-device-object ld-id)
        field (doto (.getDeclaredField (class ldo) "transport")
                (.setAccessible true))]
    (.get field ldo)))

(deftest segment-timeout-config-is-applied
  (testing ":apdu-segment-timeout (the key Wacnet exposes) reaches the transport"
    (ld/with-temp-devices
      (ld/new-local-device! {:device-id 1332 :apdu-segment-timeout 1234})
      (is (= 1234 (.getSegTimeout (transport-of 1332)))))))
