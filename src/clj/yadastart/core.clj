(ns yadastart.core
  (:require [bidi.bidi :as bidi]
            [yada.yada :as yada]
            [yada.resources.file-resource :refer [new-file-resource respond-with-file]]
            [schema.core :as s]
            [yada.resources.classpath-resource :refer [new-classpath-resource]]
            [buddy.sign.jwt :as jwt]
            [clj-time.core :as time]
            [clojure.java.io :as io])
  (:import [clojure.lang ExceptionInfo])
  (:gen-class))


(def ^:const default-port 83)
(def ^:const secret "9eLPqOKtc3wiJImA69ybMXGVjnHMbZM9+pXs")

;; aleph-server
(defonce server (atom nil))


(defmethod yada.security/verify
  "my-custom"
  [ctx {:keys [verify]}]
  (when verify
    (verify ctx)))


(defmethod yada.security/verify
  :jwt
  [ctx {:keys [cookie yada.jwt/secret] :or {cookie "session"} :as scheme}]
  (println "verify!")
  (when-not secret (throw (ex-info "Buddy JWT verifier requires a secret entry in scheme" {:scheme scheme})))
  (try
    (let [auth (some->
                (get-in ctx [:cookies cookie])
                (jwt/unsign secret))]
      auth)
    (catch ExceptionInfo e
      (if-not (or (= (ex-data e)
                     {:type :validation :cause :signature})
                  (= (ex-data e)
                     {:type :validation :cause :exp}))
        (throw e)
        )
      )))


;(yada/handler (new java.io.File "resources/templates/login.html"))

;; (merge (into {} (new-file-resource (new java.io.File "resources/templates/login.html")
;;                                                     {}))

  ;(io/resource "login.html"))
;; (yada/resource {:id ::index
;;                           :produces {:media-type "text/plain"}
;;                           :methods {:get
;;                                      {:response (fn[ctx](-> "hey there!"))}}})

(def login-res
  (yada/resource {:id ::login
                  :methods {:get {:produces {:media-type "text/html"}
                                  :response (fn [ctx] (respond-with-file ctx
                                                                         (new java.io.File "resources/public/templates/login.html")
                                                                         nil))}
                            :post {:produces "application/json"
                                   :consumes "application/x-www-form-urlencoded"
                                   :parameters {:form
                                                {:login s/Str :password s/Int}}
                                   :response (fn [ctx]
                                               (let [params (get-in ctx [:parameters :form])]
                                                 (println "Params: ")
                                                 (println params)
                                                 ;; проверка логина и пароля
                                                 (if (= ((juxt :login :password) params)
                                                        ["qwe" 123])
                                                   ;; если успешно, то записываем куки
                                                   (let [expires (time/plus (time/now) (time/minutes 15))
                                                         jwtoken (jwt/sign {:user "Qwe"
                                                                        :roles ["private/view"]
                                                                        :exp expires}
                                                                       secret)
                                                         cookie {:value jwtoken
                                                                 :expires expires
                                                                 :http-only true}]
                                                     (assoc (:response ctx)
                                                       :cookies {"session" cookie}
                                                       :body {:success true
                                                              :link "www.somelink.com"}))
                                                   ;; если не успешно
                                                   (assoc (:response ctx)
                                                     :cookies {"session" {:value "" :expires 0}}
                                                     :body {:success false
                                                            :error "fail auth"}))))}
                            }
                  :responses {400 {:produces "application/json"
                                   :response (fn[ctx](-> {:title "Error on logining:"
                                                          :error (:error ctx) }))}}

                  }))

;login-res

(def default-jwt-auth-scheme {:authentication-schemes [{:scheme :jwt
                                                        :yada.jwt/secret secret}]})

(declare bidi-routes)

(defn redirect-response
  [ctx target-route-id]
  (assoc
    (:response ctx)
    :status 302
    :headers {"location" (bidi/path-for bidi-routes target-route-id)}))

(defn redirection-response-map
  [target-route-id]
  {:produces "text/plain"
   :response (fn[ctx]
               (redirect-response ctx target-route-id))})

;;                (let [uri-info (:uri-info ctx)]
;;                    (when (nil? uri-info)
;;                      (throw (ex-info "No uri-info in context, cannot do redirect" {})))
;;                    (if-let [uri (:uri (uri-info target-route-id {}))]
;;                      (assoc (:response ctx)
;;                             :status 302
;;                             :headers {"location" uri})
;;                      (throw (ex-info (format "Redirect to unknown location: %s" target-route-id)
;;                                      {:status 500
;;                                       :target target-route-id})))))})


(defn default-access-control
  [authorization-methods]
  {:access-control (-> default-jwt-auth-scheme
                       (assoc :authorization {:methods authorization-methods}))
   :responses {403 (redirection-response-map ::login)
               401 (redirection-response-map ::login)}})


(def index-res
  (yada/resource
    {:id ::index
     :produces {:media-type "text/html"}
     :methods {:get
               {:response (fn[ctx]
                            ;; если авторизован
                            (if (yada.security/verify ctx
                                                      {:scheme :jwt
                                                       :yada.jwt/secret secret})
                              ;; редиректим на глав.страницу приложения
                              (redirect-response ctx ::app-main-page)
                              ;; иначе просто показываем главную
                              (respond-with-file ctx
                                                 (new java.io.File "resources/public/templates/index.html")
                                                 nil)))}}}))

(def bidi-routes
  ["" [["/" index-res]
       ["/login"  login-res]
       ["/app" (yada/resource
                 (merge (default-access-control {:get "private/view"})
                        {:id ::app-main-page
                         :produces "text/plain"
                         :methods {:get
                                   {:response (fn [ctx] (-> "app main page"))}}}))]
       ["/open" (yada/resource
                  {:id ::open-resource
                   :produces "text/plain"
                   :methods {:get
                             {:response (fn [ctx] (-> "open resource"))}}})]
       ["/private" (yada/resource
                     (merge (default-access-control {:get "private/view"})
                            {:id ::private-resource
                             :produces "text/plain"
                             :methods {:get
                                       {:response (fn [ctx] (-> "private resource"))}}}))]
       ["" (yada/yada (new-classpath-resource "public"))]]
   ])


;;;
;;; Server functions
;;;
(defn start-web-server!
  []
  (println "start-web-server!")
  (let [port default-port
        listener (yada/listener bidi-routes {:port port})]
    (reset! server listener)))


(defn restart!
  []
  ((:close @server))
  (start-web-server!))


(defn start-app!
  []
  (do
    (println "App started...")
    (start-web-server!)))


(defn -main [& args]
  (start-app!))
