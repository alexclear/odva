(ns odva.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-milliseconds]]
            [clojurewerkz.quartzite.jobs :as j]
            [clj-http.client :as httpclient]
            [prometheus.core :as prometheus]
            [odva.metrics :refer [init!]]
            [odva.metrics :as metrics]
            [odva.service :as service]))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server service/service))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (println "\nCreating your [DEV] server...")
  (-> service/service ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::server/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::server/routes #(route/expand-routes (deref #'service/routes))
              ;; all origins are allowed in dev mode
              ::server/allowed-origins {:creds true :allowed-origins (constantly true)}})
      ;; Wire up interceptor chains
      server/default-interceptors
      server/dev-interceptors
      server/create-server
      server/start))

(defjob GetLinks
  [ctx]
  (let [start (System/currentTimeMillis)]
    (httpclient/get "http://minfin.ru/ru/opendata/")
    (let [finish (System/currentTimeMillis)]
      (println (format "Elapsed time in millis: %1$d" (- finish start)))
      (prometheus/track-observation @metrics/store "odva" "client_http_request" (- finish start) ["minfin_ru_ru_opendata"])
      )
    )
  (println "This job does something"))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (init!)
  (let [s (-> (qs/initialize) qs/start)
        job (j/build
             (j/of-type GetLinks)
             (j/with-identity (j/key "jobs.noop.1")))
        trigger (t/build
                 (t/with-identity (t/key "triggers.1"))
                 (t/start-now)
                 (t/with-schedule (schedule
                                   (repeat-forever)
                                   (with-interval-in-milliseconds 10000))))
        ]
    (qs/schedule s job trigger))
  (println "\nCreating your server...")
  (server/start runnable-service))

;; If you package the service up as a WAR,
;; some form of the following function sections is required (for io.pedestal.servlet.ClojureVarServlet).

;;(defonce servlet  (atom nil))
;;
;;(defn servlet-init
;;  [_ config]
;;  ;; Initialize your app here.
;;  (reset! servlet  (server/servlet-init service/service nil)))
;;
;;(defn servlet-service
;;  [_ request response]
;;  (server/servlet-service @servlet request response))
;;
;;(defn servlet-destroy
;;  [_]
;;  (server/servlet-destroy @servlet)
;;  (reset! servlet nil))

