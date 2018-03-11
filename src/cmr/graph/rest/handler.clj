(ns cmr.graph.rest.handler
  (:require
   [clojusc.twig :as twig]
   [cmr.graph.demo.movie :as movie]
   [cmr.graph.health :as health]
   [cmr.graph.rest.response :as response]
   [ring.util.codec :as codec]
   [taoensso.timbre :as log]))

(defn movie-demo-graph
  [conn]
  (fn [request]
    (->> [:path-params :title]
         (get-in request)
         (movie/get-graph conn)
         (response/json request))))

(defn movie-demo-search
  [conn]
  (fn [request]
    (->> [:params :q]
         (get-in request)
         (movie/search conn)
         (response/json request))))

(defn movie-demo-title
  [conn]
  (fn [request]
    (->> [:path-params :title]
         (get-in request)
         (codec/percent-decode)
         (movie/get-movie conn)
         (response/json request))))

(defn health
  [component]
  (fn [request]
    (->> component
         health/components-ok?
         (response/json request))))

(def ping
  (fn [request]
    (response/json request {:result :pong})))

(def fallback
  (fn [request]
    (response/not-found request)))
