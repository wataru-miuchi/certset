(defproject certset "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojure/clojurescript "0.0-2197" :exclusions [org.apache.ant/ant]]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [org.clojure/tools.logging "0.3.0"]
                 [clj-logging-config "1.9.9"]
                 [ring/ring-jetty-adapter "1.2.2" :exclusions [javax.servlet/servlet-api]]
                 [com.google.api-client/google-api-client "1.17.0-rc"]
                 [com.google.http-client/google-http-client "1.17.0-rc"]
                 [com.google.http-client/google-http-client-jackson2 "1.17.0-rc"]
                 [com.google.apis/google-api-services-calendar "v3-rev88-1.19.0"]
                 [fogus/ring-edn "0.2.0"]
                 [cljs-ajax "0.2.3"]
                 [compojure "1.1.6"]
                 [om "0.6.2"]
                 [cheshire "5.3.1"]
                 ]
  :resource-paths ["resources"]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :cljsbuild {
               :builds [{:source-paths ["src"]
                         :compiler {:output-to "resources/public/js/main.js"
                                    :optimizations :whitespace
                                    :pretty-print true
                                    :preamble ["react/react.min.js"]
                                    :externs ["react/externs/react.js"]}}
                        ]}
  :main certset.core
  )