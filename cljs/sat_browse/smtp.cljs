(ns sat-browse.smtp
  (:require ["nodemailer" :as nodemailer]
            [sat-browse.firebase :refer [config]]))


(defn- create-transport []
  (let [smtp (:smtp @config)]
    (nodemailer/createTransport
      #js {:host (:hostname smtp)
           :port (js/parseInt (:port smtp))
           :secure false
           :requireTLS true
           :auth #js {:user (:username smtp)
                      :pass (:password smtp)}
           :logger false})))


(def transport
  (delay (create-transport)))


(defn send-email
  [msg]
  (.sendMail ^js @transport (clj->js msg)))
