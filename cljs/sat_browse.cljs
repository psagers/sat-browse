(ns sat-browse
  (:require ["firebase-admin" :as admin]
            ["firebase-functions" :as functions]
            ;; [ghostwheel.tracer]
            [sat-browse.cloudmailin :as cloudmailin]
            [sat-browse.request :as request]))


(defonce app (admin/initializeApp))


(defn exports []
  #js {:cloudMailin (functions/https.onRequest cloudmailin/new-inbound)
       :newRequest (-> (functions/firestore.document "requests/{docId}")
                       (.onCreate request/new-request))})
