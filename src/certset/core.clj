(ns certset.core
  (:use
    compojure.core
    ring.adapter.jetty
    ring.middleware.edn
    )
  (:require
    [certset.push :as push]
    [clojure.tools.cli :as cli]
    [clojure.string :as string]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.tools.logging :as log]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [ring.util.response :as ring]
    [cheshire.core :refer :all]
    [clj-logging-config.log4j :as log-config]
    )
  (:import
    [java.io File StringWriter]
    )
  )

(defn edn-response [body] {:status 200 :headers {"Content-Type" "application/edn; charset=UTF-8"} :body (pr-str body)})

(defn- index-page [request]
  (ring/file-response "index.html" {:root "resources/html"}))

(def log-file-path (atom nil))

(defn- set-log [path]
  (let [log-pattern "%d %5p %c{1} - %m%n"
        file-pattern-layout (org.apache.log4j.EnhancedPatternLayout. log-pattern)
        file-appender (org.apache.log4j.RollingFileAppender. file-pattern-layout path true)]
    (doto file-appender
      (.setMaxFileSize "10MB")
      (.setMaxBackupIndex 3)
      (.activateOptions))
    (log-config/set-loggers!
      "certset"
      {:name "console" :level :debug :pattern log-pattern}
      "certset"
      {:name "file" :out file-appender})
    (reset! log-file-path path)
    ))

(defn- get-log []
  (let [log-file (File. @log-file-path)]
    (if (.exists log-file)
      (slurp log-file))))

(defn- save-config [config]
  (let [w (StringWriter.)]
    (pprint/pprint config w)
    (io/copy (str w) @push/config-file)
    (read-string (slurp @push/config-file))
    ))

(defroutes main-routes
  (GET "/" [] index-page)
  (GET "/edn/config" [] (edn-response (push/get-config)))
  (POST "/edn/config" request (edn-response (save-config (:edn-params request))))
  (POST "/edn/push" [] (edn-response (push/push-calendar)))
  (GET "/edn/log" [] (edn-response {:body (get-log)}))
  (route/resources "/")
  (route/not-found "Page not found"))

(def app
  (->
    (handler/site main-routes)
    wrap-edn-params
    ))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args [
                           ["-p" "--port PORT" "Listen for connections on this port" :parse-fn #(try (Integer/parseInt %) (catch Exception _ nil))]
                           ["-c" "--config FILE" "Config FILE" :default "./config.clj"]
                           ["-l" "--log FILE" "Log FILE" :default "./logs/certset.log"]
                           ["-h" "--help" "This help"]
                           ])
        file (File. (:config options))]
    (set-log (:log options))
    (when errors
      (println (string/join \newline errors))
      (System/exit 1))
    (when (:help options)
      (println summary)
      (System/exit 0))
    (when-not (.exists file)
      (println (:config options) "none")
      (System/exit 1))

    (reset! push/config-file file)

    (if (:port options)
      (run-jetty app {:port (:port options)})
      (push/push-calendar))
    )
  )
