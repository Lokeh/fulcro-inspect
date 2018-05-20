(ns fulcro.inspect.remote
  (:require [fulcro.client :as fulcro]
            [fulcro.client.primitives :as fp]
            [fulcro.inspect.remote.transit :as encode]
            [goog.object :as gobj]
            [fulcro.inspect.ui.data-history :as data-history]
            [clojure.set :as set]
            [fulcro.inspect.ui.network :as network]
            [fulcro.client.network :as f.network]))

(defonce started?* (atom false))
(defonce apps* (atom {}))

(def app-id-key :fulcro.inspect.core/app-id)

(defn post-message [type data]
  (.postMessage js/window #js {:fulcro-inspect-remote-message (encode/write {:type type :data data :timestamp (js/Date.)})} "*"))

(defn listen-local-messages []
  (.addEventListener js/window "message"
    (fn [event]
      (when (and (= (.-source event) js/window)
                 (gobj/getValueByKeys event "data" "fulcro-inspect-devtool-message"))
        (js/console.log "DEVTOOL MESSAGE" event)))
    false))

(defn find-remote-server []
  )

(defn app-name [reconciler]
  (or (some-> reconciler fp/app-state deref app-id-key)
      (some-> reconciler fp/app-root (gobj/get "displayName") symbol)
      (some-> reconciler fp/app-root fp/react-type (gobj/get "displayName") symbol)))

(defn inspect-network-init [network app]
  (some-> network :options ::app* (reset! app)))

(defn transact!
  ([tx]
   (post-message ::transact-client {::tx tx}))
  ([ref tx]
   (post-message ::transact-client {::tx-ref ref ::tx tx})))

(gobj/set js/window "PING_PORT"
  (fn []
    (post-message ::ping {:msg-id (random-uuid)})))

(defn update-inspect-state [app-id state]
  (transact! [::data-history/history-id [app-id-key app-id]]
             [`(data-history/set-content ~state) ::data-history/history]))

(defn inspect-app [target-app]
  (let [state* (some-> target-app :reconciler :config :state)
        app-id (app-name (:reconciler target-app))]

    (inspect-network-init (-> target-app :networking :remote) target-app)

    (add-watch state* app-id
      #(update-inspect-state app-id %4))

    (swap! state* assoc ::initialized true)
    #_new-inspector))

(defn inspect-tx [{:keys [reconciler] :as env} info]
  (if (fp/app-root reconciler) ; ensure app is initialized
    (let [tx     (-> (merge info (select-keys env [:old-state :new-state :ref :component]))
                     (update :component #(gobj/get (fp/react-type %) "displayName"))
                     (set/rename-keys {:ref :ident-ref}))
          app-id (app-name reconciler)]
      (if (-> reconciler fp/app-state deref ::initialized)
        (transact! [:fulcro.inspect.ui.transactions/tx-list-id [app-id-key app-id]]
                   [`(fulcro.inspect.ui.transactions/add-tx ~tx) :fulcro.inspect.ui.transactions/tx-list])))))

;;; network

(defrecord TransformNetwork [network options]
  f.network/NetworkBehavior
  (serialize-requests? [this]
    (try
      (f.network/serialize-requests? network)
      (catch :default _ true)))

  f.network/FulcroNetwork
  (send [_ edn ok error]
    (let [{::keys [transform-query transform-response transform-error app*]
           :or    {transform-query    (fn [_ x] x)
                   transform-response (fn [_ x] x)
                   transform-error    (fn [_ x] x)}} options
          req-id (random-uuid)
          env    {::request-id req-id
                  ::app        @app*}]
      (if-let [edn' (transform-query env edn)]
        (f.network/send network edn'
          #(->> % (transform-response env) ok)
          #(->> % (transform-error env) error))
        (ok nil))))

  (start [this]
    (try
      (f.network/start network)
      (catch ::default e
        (js/console.log "Error starting sub network" e)))
    this))

(defn transform-network [network options]
  (->TransformNetwork network (assoc options ::app* (atom nil))))

(defrecord TransformNetworkI [network options]
  f.network/FulcroRemoteI
  (transmit [_ {::f.network/keys [edn ok-handler error-handler]}]
    (let [{::keys [transform-query transform-response transform-error app*]
           :or    {transform-query    (fn [_ x] x)
                   transform-response (fn [_ x] x)
                   transform-error    (fn [_ x] x)}} options
          req-id (random-uuid)
          env    {::request-id req-id
                  ::app        @app*}]
      (if-let [edn' (transform-query env edn)]
        (f.network/transmit network
          {::f.network/edn           edn'
           ::f.network/ok-handler    #(->> % (transform-response env) ok-handler)
           ::f.network/error-handler #(->> % (transform-error env) error-handler)})
        (ok-handler nil))))

  (abort [_ abort-id] (f.network/abort network abort-id)))

(defn transform-network-i [network options]
  (->TransformNetworkI network (assoc options ::app* (atom nil))))

(defn inspect-network
  ([remote network]
   (let [ts {::transform-query
             (fn [{::keys [request-id app]} edn]
               (let [app-id (app-name (:reconciler app))]
                 (transact! [::network/history-id [app-id-key app-id]]
                            [`(network/request-start ~{::network/remote      remote
                                                       ::network/request-id  request-id
                                                       ::network/request-edn edn})]))
               edn)

             ::transform-response
             (fn [{::keys [request-id app]} response]
               (let [app-id (app-name (:reconciler app))]
                 (transact! [::network/history-id [app-id-key app-id]]
                            [`(network/request-finish ~{::network/request-id   request-id
                                                        ::network/response-edn response})]))
               response)

             ::transform-error
             (fn [{::keys [request-id app]} error]
               (let [app-id (app-name (:reconciler app))]
                 (transact! [::network/history-id [app-id-key app-id]]
                            [`(network/request-finish ~{::network/request-id request-id
                                                        ::network/error      error})]))
               error)}]
     (cond
       (implements? f.network/FulcroNetwork network)
       (transform-network network ts)

       (implements? f.network/FulcroRemoteI network)
       (transform-network-i network
         (update ts ::transform-response (fn [tr] (fn [env {:keys [body] :as response}]
                                                    (tr env body)
                                                    response))))

       :else
       (js/console.warn "Invalid network" {:network network})))))

(defn install [_]
  (js/document.documentElement.setAttribute "__fulcro-inspect-remote-installed__" true)

  (when-not @started?*
    (js/console.log "Installing Fulcro Inspect" {})

    (reset! started?* true)

    (fulcro/register-tool
      {::fulcro/tool-id
       ::fulcro-inspect-remote

       ::fulcro/app-started
       (fn [{:keys [reconciler] :as app}]
         (let [state* (some-> reconciler fp/app-state)
               app-id (random-uuid)]
           (post-message ::init-app {app-id-key      app-id
                                     ::app-name      (app-name reconciler)
                                     ::initial-state @state*})

           (swap! apps* assoc app-id app)
           (swap! state* assoc app-id-key app-id)

           (inspect-app app))
         app)

       ::fulcro/network-wrapper
       (fn [networks]
         (into {} (map (fn [[k v]] [k (inspect-network k v)])) networks))

       ::fulcro/tx-listen
       inspect-tx})

    (listen-local-messages)))
