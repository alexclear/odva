(ns odva.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-milliseconds]]
            [clojurewerkz.quartzite.jobs :as j]
            [clj-http.client :as httpclient]
            [prometheus.core :as prometheus]
            [odva.metrics :refer [init!]]
            [odva.metrics :as metrics]
            [clj-yaml.core :as yaml]
            [clojure.tools.cli :refer [parse-opts]]
            [odva.service :as service]))

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
  (let [start (System/currentTimeMillis) m (qc/from-job-data ctx)]
    (httpclient/get "http://minfin.ru/ru/opendata/")
    (let [finish (System/currentTimeMillis)]
      (println (format "Elapsed time in millis: %1$d" (- finish start)))
      (prometheus/track-observation @metrics/store (get m "store-name") "client_http_request" (- finish start) ["minfin_ru_ru_opendata"])
      )
    )
  (println "This job does something"))

(def cli-options
  [
   ["-p" "--port PORT" "Port number"
    :default 8080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-i" "--interval SECS" "Interval between probes in seconds"
    :default 10
    :parse-fn #(Integer/parseInt %)]
   ["-s" "--store STORE" "Metrics store name"
    :default "odva"]
   ["-h" "--help"]])

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (println (str "options: " (.toString options)))
    (init! (:store options))
    (let [s (-> (qs/initialize) qs/start)
          job (j/build
               (j/of-type GetLinks)
               (j/using-job-data {"store-name" (:store options)})
               (j/with-identity (j/key "jobs.noop.1")))
          trigger (t/build
                   (t/with-identity (t/key "triggers.1"))
                   (t/start-now)
                   (t/with-schedule (schedule
                                     (repeat-forever)
                                     (with-interval-in-milliseconds (* 1000 (:interval options))))))
          ]
      (qs/schedule s job trigger))
    (println "\nCreating your server...")
    (try
      (println (.toString (merge service/service {:io.pedestal.http/port (:port options)})))
      (server/start (server/create-server (merge service/service {:io.pedestal.http/port (:port options)})))
      (catch java.net.BindException e (println (str "caught exception: " (.toString e))) (System/exit 1))
      )
    )
  )

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

