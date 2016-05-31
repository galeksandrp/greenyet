(ns greenyet.core
  (:require [clj-yaml.core :as yaml]
            [clojure
             [string :as str]
             [walk :refer [keywordize-keys]]]
            [clojure.java.io :as io]
            [greenyet
             [config :as config]
             [poll :as poll]
             [view :as view]
             [styleguide :as styleguide]]
            [ring.middleware
             [not-modified :as not-modified]
             [params :as params]
             [resource :as resource]]
            [ring.util
             [response :refer [charset content-type header response]]
             [time :refer [format-date]]])
  (:import java.io.FileNotFoundException))

(def ^:private config-dir (System/getenv "CONFIG_DIR"))

(def ^:private polling-interval-in-ms (or (some-> (System/getenv "POLLING_INTERVAL")
                                                  Integer/parseInt)
                                          (some-> (System/getenv "TIMEOUT")
                                                  Integer/parseInt)
                                          5000))

(def ^:private config-params [["CONFIG_DIR" (or config-dir
                                                "")]
                              ["POLLING_INTERVAL" polling-interval-in-ms]
                              ["PORT" (or (System/getenv "PORT")
                                          3000)]])

(def development? (= "development" (System/getProperty "greenyet.environment")))

(defn- page-template []
  (-> "index.template.html" io/resource slurp))

(defn- styleguide-template []
  (-> "styleguide.template.html" io/resource slurp))

(defn- environment-names []
  (-> "environment_names.yaml" io/resource slurp yaml/parse-string))

(defn- query-param-as-vec [params key]
  (let [value (get params key)
        value-vector (if (string? value)
                       (vector value)
                       value)]
    (seq (mapcat #(str/split % #",") value-vector))))

(defn- html-response [body]
  (-> (response body)
      (content-type "text/html")
      (charset "UTF-8")))


(defn- render [{params :params uri :uri}]
  (if (and development?
           (= "/styleguide" uri))
    (html-response (styleguide/render (keywordize-keys params) (styleguide-template)))
    (let [[host-with-statuses last-changed] @poll/statuses]
      (-> (html-response (view/render host-with-statuses
                                      (query-param-as-vec params "systems")
                                      (page-template)
                                      (environment-names)))
          (header "Last-Modified" (format-date (.toDate last-changed)))))))


(def config-help (str/join "\n"
                           ["To kick off, why don't you create a file hosts.yaml with"
                            ""
                            "- hostname: localhost"
                            "  system: greenyet"
                            "  environment: Development"
                            ""
                            "and a status_url.yaml with"
                            ""
                            "- url: http://%hostname%:3000/"
                            "  system: greenyet"
                            ""]))

(defn init []
  (try
    (poll/start-polling (config/hosts-with-config config-dir) polling-interval-in-ms)
    (catch FileNotFoundException e
      (binding [*out* *err*]
        (println (.getMessage e))
        (println)
        (println config-help))
      (System/exit 1)))
  (println "Starting greenyet with config")
  (->> config-params
       (map (fn [[env-var value]]
              (format "  %s: '%s'" env-var value)))
       (map println)
       doall))

(def handler
  (-> render
      params/wrap-params
      (resource/wrap-resource "public")
      not-modified/wrap-not-modified))
