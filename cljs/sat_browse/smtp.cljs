(ns sat-browse.smtp
  (:require ["nodemailer" :as nodemailer]
            [sat-browse.firebase :refer [config]]))


(defprotocol Transport
  (deliver-msg [this msg]))


(defrecord Nodemailer [^js transport]
  Transport
  (deliver-msg [_this msg]
    (.sendMail transport (clj->js msg))))


(defn- smtp-transport []
  (let [smtp (:smtp @config)]
    (-> (nodemailer/createTransport
          #js {:host (:hostname smtp)
               :port (js/parseInt (:port smtp))
               :secure false
               :requireTLS true
               :auth #js {:user (:username smtp)
                          :pass (:password smtp)}
               :logger false})
        ->Nodemailer)))


(defrecord Console []
  Transport
  (deliver-msg [_this msg]
    (prn msg)))


(defn- console-transport []
  (->Console))


(defrecord Null []
  Transport
  (deliver-msg [_this _msg]
    (js/console.warn "Dropping outbound email: no transport configured.")))


(defn- null-transport []
  (->Null))


(def transport
  (delay (case (-> @config :email :transport)
           "smtp" (smtp-transport)
           "console" (console-transport)
           (null-transport))))


(defn send-email
  [msg]
  (-> (js/Promise.resolve msg)
      (.then #(deliver-msg @transport %))))
