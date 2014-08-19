(ns certset.push
  (:require
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :as log]
    [certmon.core :as certmon])
  (:import
    (com.google.api.client.googleapis.auth.oauth2 GoogleCredential$Builder)
    (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
    (com.google.api.client.json.jackson2 JacksonFactory)
    (com.google.api.services.calendar Calendar CalendarScopes Calendar$Builder)
    (com.google.api.services.calendar.model Event EventAttendee EventDateTime CalendarListEntry EventReminder)
    (com.google.api.client.util DateTime)
    (java.io File StringWriter)
    (java.util Date TimeZone Locale)
    (java.text SimpleDateFormat)))

(def HTTP_TRANSPORT (GoogleNetHttpTransport/newTrustedTransport))
(def JSON_FACTORY (JacksonFactory.))
(def config-file (atom {}))

(defn get-config []
  (read-string (slurp @config-file)))

(defn credential []
  (let [config (get-config)
        credential
        (doto (GoogleCredential$Builder.)
          (.setTransport HTTP_TRANSPORT)
          (.setJsonFactory JSON_FACTORY)
          (.setServiceAccountId (get-in config [:AUTH :SERVICE_ACCOUNT_EMAIL]))
          (.setServiceAccountScopes (seq [CalendarScopes/CALENDAR]))
          (.setServiceAccountPrivateKeyFromP12File (File. (get-in config [:AUTH :P12_FILE]))))]
    (.build credential)))

(defn calendar []
  (let [creds (credential)
        service
        (doto (Calendar$Builder. HTTP_TRANSPORT JSON_FACTORY creds)
          (.setApplicationName "SSL Certificate to Calendar"))]
    (.build service)))

(defn create-event [summary description expire]
  (doto (Event.)
    (.setSummary summary)
    (.setDescription description)
    (.setAttendees [(doto (EventAttendee.) (.setEmail (get-in (get-config) [:CALENDAR :ID])) (.setResponseStatus "accepted"))])
    (.setStart (doto (EventDateTime.) (.setDateTime expire)))
    (.setEnd (doto (EventDateTime.) (.setDateTime expire)))
    ))

(defn upsert-event [service events cert-record]
  (let [df (SimpleDateFormat. "EEE MMM dd HH:mm:ss z yyyy" Locale/ENGLISH)
        expire-date (.parse df (:expire cert-record))
        expire (DateTime. expire-date (TimeZone/getTimeZone "JST"))
        summary (:cn cert-record)
        description (let [w (StringWriter.)] (clojure.pprint/pprint cert-record w) (str w))
        created-event (first (filter #(= (get % "summary") summary) events))]
    (cond
      ; 新規作成
      (nil? created-event)
      (let [event (create-event summary description expire)]
        (log/info (str summary " : insert"))
        (-> service .events (.insert "primary" event) .execute))
      ; なし
      (= (get created-event "description") description)
      (log/info (str summary " : none"))
      ; 更新
      :default
      (let [event (create-event summary description expire)]
        (log/info (str summary " : update"))
        (-> service .events (.update "primary" (get created-event "id") event) .execute))
      )))

(defn push-calendar []
  (try
    (log/info "***** start *****")
    (let [config (get-config)
          service (calendar)
          dateTime (DateTime. (Date.))
          events ; startしていないイベントのみ取得
          (keep
            #(if (< (.getValue dateTime) (.getValue (get-in % ["start" "dateTime"])))
               (-> service .events (.get "primary" (get % "id")) .execute))
            (-> service .events (.list (get-in config [:CALENDAR :ID])) (.setTimeMin dateTime) .execute (get "items")))]
      (doseq [cert (:CERT config)]
        (let [record (first (certmon/get-cert (:DOMAIN cert) 443))
              event (upsert-event service events record)]
          (if event (log/info event))
          )
        )
      )
    (catch Exception e
      (log/error e e)
      (throw e))
    (finally
      (log/info "***** end *****")
      )
    )
  )