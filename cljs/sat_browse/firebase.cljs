(ns sat-browse.firebase
  "Convenience wrappers for Firebase."
  (:require ["firebase-admin" :as admin]
            ["firebase-functions" :as functions]
            ["fs" :as fs]))


(defn- local-config []
  (try
    (-> (fs/readFileSync ".runtimeconfig.json")
        js/JSON.parse
        (js->clj :keywordize-keys true))
    (catch js/Error _)))


(def config
  (delay (or (local-config)
             (js->clj (functions/config)))))


(def db
  (delay (admin/firestore)))
