(ns sat-browse.cloudmailin
  (:require ["url" :refer [URL]]
            [cljs-bean.core :refer [->clj]]
            [clojure.string :as str]
            [goog.object :as gobj]
            [sat-browse.firebase :refer [config db]]))


(defn- abort
  "Bails out of the request processing early."
  [req]
  (throw (ex-info "Abort" {:req (assoc req :abort? true)})))


(defn- validate-request
  [req]
  (let [expected (-> ^js @config :cloudmailin :key)
        actual (gobj/getValueByKeys (:http-req req) "query" "key")]
    (if (and (not-empty expected) (not= expected actual))
      (abort (assoc req :status 403))
      req)))


(defn- get-email-doc
  [email]
  (-> ^js @db (.collection "emails") (.doc email) .get))


(defn- lookup-user
  [req]
  (if-some [email (-> req :body :envelope :from)]
    (-> (get-email-doc email)
        (.then (fn [^js doc]
                 (if (.-exists doc)
                   (assoc req :email email, :email-doc doc)
                   (abort req)))))
    (abort req)))


(defn- text->urls
  [text]
  (when (string? text)
    (letfn [(valid-url? [value] (try
                                  (and (re-find #"^https?://" value)
                                       (boolean (URL. value)))
                                  (catch js/Error _
                                    false)))]
      (eduction
        (map str/trim)
        (filter valid-url?)
        (str/split-lines text)))))


(comment
  (text->urls "https://example.com/
              bogus
              http://example.net/a/b/c.png?q=6 
              ftp://example.org/"))


(defn- add-request-docs
  [{:keys [email] :as req}]
  (let [urls (vec (text->urls (-> req :body :plain)))
        requests (.collection ^js @db "requests")
        batch (.batch ^js @db)]
    (doseq [url urls]
      (.set batch
            (.doc requests)
            #js {:url url, :email email, :received (js/Date.)}))

    (-> (.commit batch)
        (.then #(do (js/console.log "Added %d requests for %s" (count urls) email)
                    req)))))


(defn- finish
  [^js res, {:keys [status]}]
  (.sendStatus res (or status 201)))


(defn- handle-error
  [^js res, error]
  (let [{:keys [req]} (ex-data error)]
    (if (:abort? req)
      (finish res req)
      (do (js/console.error error)
          (.status res 500)
          (.send res (ex-message error))))))


(defn new-inbound
  [^js http-req, ^js res]
  (let [req {:http-req http-req
             :body (-> http-req .-body ->clj)}]
    (-> (js/Promise.resolve req)
        (.then validate-request)
        (.then lookup-user)
        (.then add-request-docs)
        (.then #(finish res %)
               #(handle-error res %))
        (.finally #(.end res)))))
