(ns sat-browse.request
  (:require ["@tinyhttp/content-disposition" :as content-disposition]
            ["html-to-text" :as html-to-text]
            ["got" :as got]
            ["url" :refer [URL]]
            ;; [cljs.spec.alpha :as s]
            [clojure.string :as str]
            ;; [ghostwheel.core :refer [>defn- =>]]
            [goog.object :as gobj]
            [sat-browse.firebase :refer [config]]
            [sat-browse.smtp :as smtp]
            [taoensso.encore :refer [assoc-some]]))


;; (defn- error? [e] (instance? js/Error e))
;; (defn- promise? [p] (instance? js/Promise p))

;; (s/def ::url string?)
;; (s/def ::email string?)
;; (s/def ::abort? boolean?)
;; (s/def ::http-response #(and (js-in "body" %)
;;                             (js-in "rawBody" %)
;;                             (js-in "url" %)))
;; (s/def ::html string?)
;; (s/def ::content-type string?)
;; (s/def ::text string?)
;; (s/def ::title string?)
;; (s/def ::attachments (s/coll-of ::attachment, :kind vector?))
;; (s/def ::error string?)

;; (s/def ::filename string?)
;; (s/def ::content #(instance? js/Buffer %))
;; (s/def ::contentType string?)
;; (s/def ::attachment (s/keys :req-un [::filename ::content ::contentType]))

;; (s/def ::req (s/keys :req-un [::url ::email]
;;                     :opt-un [::abort? ::http-response ::html ::content-type
;;                               ::text ::title ::attachments ::error]))

;; (s/def ::req-promise promise?)


(defn- valid-request?
  "True if the initial request record satisfies minimal requirements."
  [req]
  ;; [::req => boolean?]
  (-> (and (:url req)
           (:email req))
      boolean))


(defn- simple-content-type
  "Extracts the main part of a content-type header value."
  [content-type]
  ;; [::content-type => ::content-type]
  (some->> content-type
           (re-find #"^[^;]*")
           (str/lower-case)))


(defn- abort
  "Bails out of the request processing early.

  This will send a response to the requestor rather than report and unhandled
  error."
  [msg req]
  ;; [string? ::req => any?]
  (throw (ex-info msg {:req (assoc req :abort? true)})))


(defn- response->content-disposition-filename
  "Extracts the file name from a response's content-dispositio header, if any."
  [^js http-response]
  ;; [::http-response => ::filename]
  (when-some [value (gobj/getValueByKeys http-response "headers" "content-disposition")]
    (let [result (content-disposition/parse value)]
      (-> result .-parameters .-filename))))


(defn- response->url-filename
  "Extracts the last path component of a response's final URL."
  [^js http-response]
  ;; [::http-response => ::filename]
  (some-> http-response .-url (str/split "/") last not-empty js/decodeURIComponent))


(defn- response->filename
  "Extracts an appropriate filename from an HTTP response."
  [http-response]
  ;; [::http-response => ::filename]
  (or (response->content-disposition-filename http-response)
      (response->url-filename http-response)))


(defn- handle-http-error
  "Traps got/HTTPError and converts it into an abort."
  [req, ^js error]
  ;; [::req error? => any?]
  (if (instance? got/HTTPError error)
    (let [msg (ex-message error)
          http-response (.-response error)]
      (abort msg (assoc req :http-response http-response)))
    (throw error)))


(defn- fetch-resource
  "Downloads the requested URL. Adds req keys :http-response, :final-url."
  [{:keys [url] :as req}]
  ;; [::req => ::req-promise]
  (try
    (URL. url)
    (catch js/Error _
      (abort "This does not appear to be a valid URL." req)))

  (-> (got url #js {:throwHttpErrors true})
      (.then #(assoc req :http-response %, :final-url (-> % .-url)))
      (.catch #(handle-http-error req %))))


(defn- req->html-result
  "Extracts results from a successful HTML response."
  [{:keys [http-response]}]
  ;; [::req => (s/keys :req-un [::html ::title ::text])]
  (let [html (.-body http-response)]
    {:html html
     :title (-> (re-find #"<title(?: .*)?>(.*?)</title>" html)
                (second))
     :text (html-to-text/fromString html)}))


(defn- req->other-result
  "Extracts results from a successful non-HTML response (such as an image)."
  [{:keys [content-type ^js http-response]}]
  ;; [::req => (s/keys :req-un [::title ::text ::attachments])]
  (let [filename (response->filename http-response)]
    {:title filename
     :text (str "This URL returned a file of type " (simple-content-type content-type) "\n\n")
     :attachments [{:filename filename
                    :content (.-rawBody http-response)
                    :contentType content-type}]}))


(defn- process-resource
  "Processes the successful http-response in a req."
  [{:keys [^js http-response] :as req}]
  ;; [::req => ::req-promise]
  (let [ctype (gobj/getValueByKeys http-response "headers" "content-type")
        req (assoc req :content-type ctype)]
    (merge req (case (simple-content-type ctype)
                 "text/html" (req->html-result req)
                 (req->other-result req)))))


(defn- handle-abort
  "Traps aborts and turns them into normal results for the requester."
  [error]
  ;; [error? => ::req]
  (let [{:keys [req]} (ex-data error)]
    (if (:abort? req)
      (let [title (ex-message error)
            text (str  "We were unable to load this URL: " title)]
        (assoc req :title title, :text text))
      (throw error))))


(defn- send-result
  "Sends the final result back to the requester's email address."
  [{:keys [email url final-url content-type title text attachments] :as req}]
  ;; [::req => ::req-promise]
  (let [msg (assoc-some {:from (-> @config :email :sender)
                         :to email
                         :subject (or title final-url url)}
                        :text (str (or final-url url) "\n\n\n" text)
                        :attachments attachments)]
    (-> (smtp/send-email msg)
        (.then #(do (js/console.log "Delivered %s resource to %s"
                                    (simple-content-type content-type)
                                    email)
                    req)))))


(defn- handle-error
  "Deals with unhandled errors with logging and notifications."
  [{:keys [email url doc-ref] :as req} error]
  ;; (when (and goog/DEBUG (:cljs.spec.alpha/problems (ex-data error)))
  ;;   (throw error))

  (let [doc-id (some-> doc-ref .-id)
        msg {:from (-> @config :email :sender)
             :to email
             :bcc "psagers@ignorare.net"
             :subject "Oops..."
             :text (str "Something went wrong processing your request. That's why we call it a prototype. We'll have a look.

Request ID: " doc-id "
URL: " url "

" error)}]

    (js/console.error error)
    (js/console.error "Failed to complete request %s for %s" doc-id email)

    (-> (smtp/send-email msg)
        (.then #(assoc req :error (ex-message error))))))


(defn- update-request
  "Updates the Firestore request document after all processing is finished."
  [{:keys [doc-ref] :as req}]
  ;; [::req => promise?]
  (let [updates (assoc (select-keys req [:final-url :content-type :title :error :abort?])
                       :completed (js/Date.))]
    (.update doc-ref (clj->js updates))))


(defn- handle-request
  "Entry point for handling a new request.

  This pipeline accumulates information in a single map called req."
  [req]
  ;; [::req => promise?]
  (-> (js/Promise.resolve req)
      (.then fetch-resource)
      (.then process-resource)
      (.catch handle-abort)
      (.then send-result)
      (.catch #(handle-error req %))
      (.then update-request)))


(defn new-request
  "Firebase function handler.

  Extracts the necessary information from the Firestore request document and
  kicks off the process."
  [^js snapshot, _context]
  (let [data (.data snapshot)
        req {:url (.-url data)
             :email (.-email data)
             :doc-ref (.-ref snapshot)}]
    (if (valid-request? req)
      (handle-request req)
      (js/console.error (str "Invalid request: " (.-id snapshot))))))
