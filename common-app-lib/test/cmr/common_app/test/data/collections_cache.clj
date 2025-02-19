(ns cmr.common-app.test.data.collections-cache
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.generators :as gen]
   [cmr.common-app.data.collections-for-gran-acls-by-concept-id-cache :as coll-gran-acls-cache]
   [cmr.common.hash-cache :as hash-cache]
   [cmr.common.joda-time :as joda-time]
   [cmr.common.util :refer [are3]]
   [cmr.redis-utils.config :as redis-config]
   [cmr.redis-utils.redis-hash-cache :as redis-hash-cache]
   [cmr.redis-utils.test.test-util :as test-util]))

(defn- random-text
  "Create a random string by combining all the values from gen/string-alphanumeric"
  []
  (apply str (vec (gen/sample gen/string-alphanumeric))))

(deftest make-dates-safe-for-serialize-test
  "Confirm that an object can be serialized to text and then back"
  (testing "round trip"
    (let [some-text (random-text)
          some-date "2024-12-31T4:3:2"
          supplied-data {:point-of-time some-date :a-field some-text}
          expected-date "2024-12-31T04:03:02.000Z"
          actual (-> supplied-data
                     coll-gran-acls-cache/time-strs->clj-times
                     coll-gran-acls-cache/clj-times->time-strs)]
      (is (= some-text (:a-field actual)) "field should not change")
      (is (= expected-date (str (:point-of-time actual))) "Date should exist"))))

(use-fixtures :each test-util/embedded-redis-server-fixture)

(def get-collection-for-gran-acls #'coll-gran-acls-cache/get-collection-for-gran-acls)

(defn create-collection-for-gran-acls-test-entry
  [provider-id entry-title coll-concept-id]
  {:concept-type :collection,
   :provider-id provider-id,
   :EntryTitle entry-title,
   :AccessConstraints {:Value 1},
   ;; this is specific timestamp for tests, do not change without changing converted timestamps in below unit tests
   :TemporalExtents [{:RangeDateTimes [{:BeginningDateTime "1984-05-01T00:00:00.000Z", :EndingDateTime nil}]}],
   :concept-id coll-concept-id})

(deftest get-collection-gran-acls-by-concept-id-test
  (let [coll-by-concept-id-cache-key coll-gran-acls-cache/coll-by-concept-id-cache-key
        coll-by-concept-id-cache     (redis-hash-cache/create-redis-hash-cache {:keys-to-track [coll-by-concept-id-cache-key]
                                                                                :read-connection (redis-config/redis-read-conn-opts)
                                                                                :primary-connection (redis-config/redis-conn-opts)})
        _                            (hash-cache/reset coll-by-concept-id-cache coll-by-concept-id-cache-key)
        context                      {:system {:caches {coll-by-concept-id-cache-key coll-by-concept-id-cache}}}
        test-coll1                   (create-collection-for-gran-acls-test-entry "TEST_PROV1" "EntryTitle1" "C123-TEST_PROV1")
        test-coll2                   (create-collection-for-gran-acls-test-entry "TEST_PROV1" "EntryTitle8" "C888-TEST_PROV1")
        converted-test-coll2         {:concept-type :collection,
                                      :provider-id "TEST_PROV1",
                                      :EntryTitle "EntryTitle8",
                                      :AccessConstraints {:Value 1},
                                      :TemporalExtents [{:RangeDateTimes [{:BeginningDateTime #=(joda-time/date-time 452217600000 "UTC"), :EndingDateTime nil}]}],
                                      :concept-id "C888-TEST_PROV1"}]
  ;; populate the cache
  (hash-cache/set-value coll-by-concept-id-cache coll-by-concept-id-cache-key "C123-TEST_PROV1" test-coll1)
  (hash-cache/set-value coll-by-concept-id-cache coll-by-concept-id-cache-key "C456-TEST_PROV1" {})

  (are3 [expected coll-concept-id]
        (is (= expected (get-collection-for-gran-acls context coll-concept-id)))

        "Collection found in cache -> return expected cache"
        {:concept-type :collection,
         :provider-id "TEST_PROV1",
         :EntryTitle "EntryTitle1",
         :AccessConstraints {:Value 1},
         :TemporalExtents [{:RangeDateTimes [{:BeginningDateTime #=(joda-time/date-time 452217600000 "UTC"), :EndingDateTime nil}]}],
         :concept-id "C123-TEST_PROV1"}
        "C123-TEST_PROV1")

  (testing "Testing when collection doesn't exist in cache or elastic -> Then returns nil collection"
   ;; mock the set-cache func
   (with-redefs-fn {#'coll-gran-acls-cache/set-cache (fn [context coll-concept-id] nil)}
     #(is (= nil (get-collection-for-gran-acls context "C000-NON_EXISTENT")))))

  (testing "Testing when collection cache has collection, but it is empty map in cache and elastic -> Then should find the collection in elastic, if still empty then returns empty coll"
    (with-redefs-fn {#'coll-gran-acls-cache/set-cache (fn [context coll-concept-id] {})}
      #(is (= {} (get-collection-for-gran-acls context "C456-TEST_PROV1")))))

  (testing "Testing when collection is not in cache, but exists in elastic -> Then should find the collection in elastic and add to cache"
    (with-redefs-fn {#'coll-gran-acls-cache/set-cache (fn [context coll-concept-id] test-coll2)}
      #(is (= converted-test-coll2 (get-collection-for-gran-acls context "C888-TEST_PROV1")))))))
