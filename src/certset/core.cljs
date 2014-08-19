(ns certset.core
  (:require
    [clojure.string :as string]
    [clojure.data :as data]
    [cljs.core.async :refer [put! chan <!]]
    [ajax.core :refer [GET POST]]
    [om.core :as om :include-macros true]
    [om.dom :as dom :include-macros true]
    )
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  )

(enable-console-print!)

(defn- sel [s] (array-seq (.querySelectorAll js/document s)))

(def aside-elem (first (sel "aside")))
(def article-elem (first (sel "article")))

(def app-state (atom {:_show "CALENDAR" :_menu ["CALENDAR" "SETTING" "LOG"]}))

(defn- set-cursor
  ([] (set-cursor "default"))
  ([cursor] (-> js/document .-body .-style .-cursor (set! cursor))))

(defn- post-save [app]
  (.clear js/toastr)
  (POST "/edn/config"
    {:params (into {} (remove #(re-find #"^_" (-> % key name)) app))
     :handler #(.success js/toastr "Saved")
     :error-handler #(.error js/toastr "Error")
     :response-format :edn}))

(defn- push-calendar []
  (set-cursor "wait")
  (POST "/edn/push"
    {:handler #(.success js/toastr "Pushed")
     :error-handler #(.error js/toastr "Error")
     :response-format :edn
     :finally set-cursor}))

(defn- add-collection [app owner state refs]
  (let [add
        (into {}
          (keep
            #(when-let [value (not-empty (get state %))]
               {% value})
            refs))]
    (when (every? add refs)
      (om/transact! app :CERT #(conj % add))
      (doseq [k refs]
        (om/set-state! owner k ""))
      (om/refresh! owner)
      )))

(defn- handle-change [e owner state & refs]
  (om/set-state! owner refs (.. e -target -value)))

(defn- handle-error [value]
  (and (empty? value) "error"))

(defn- collection-view [app owner opt]
  (reify
    om/IRenderState
    (render-state [this {:keys [change delete]}]
      (apply dom/tr nil
        (conj
          (mapv
            #(dom/td nil (dom/input #js {:type "text" :value (val %) :className (handle-error (val %))
                                         :onChange (fn [e] (put! change {:obj @app :row-key opt :col-key (key %) :value (.. e -target -value)}))}))
            app)
          (dom/td nil (dom/button #js {:onClick (fn [e] (put! delete {:obj @app :row-key opt}))} "Delete"))))
      )))

(defn- config-edit [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:delete (chan)
       :change (chan)}
      )
    om/IWillMount
    (will-mount [_]
      (let [delete (om/get-state owner :delete)
            change (om/get-state owner :change)]
        (go (while true
              (alt!
                delete
                ([{:keys [obj row-key]}]
                  (om/transact! app row-key
                    (fn [xs] (if (> (count xs) 1) (vec (remove #(= obj %) xs)) xs))))
                change
                ([{:keys [obj row-key col-key value]}]
                  (if row-key
                    (om/transact! app row-key
                      (fn [xs] (mapv #(if (= obj %) (assoc obj col-key value) %) xs)))
                    (om/update! app col-key value)
                    ))
                )))
        ))
    om/IDidUpdate
    (did-update [this prev-props prev-state]
      (om/set-state! owner :error (> (count (sel "input.error")) 0))
      )
    om/IRenderState
    (render-state [this state]
      (apply dom/div #js {:className (if (not= (:_show app) "SETTING") "hide")}
        (conj
          (vec (mapcat
                 (fn [[k v]]
                   [(dom/h2 #js {:className "title"} (name k))
                    (cond
                      (map? v)
                      (apply dom/div #js {:className "body"}
                        (dom/a #js {:href "#" :className "edit" :onClick (fn [e] (om/set-state! owner :edit true) (.preventDefault e))} "edit")
                        (if (:edit state)
                          (mapcat
                            #(vector
                               (dom/h3 #js {:className "title"} (name (key %)))
                               (dom/input #js {:type "text" :value (val %) :className (handle-error (val %))
                                               :onChange (fn [e] (put! (:change state) {:obj @app :col-key [k (key %)] :value (.. e -target -value)}))})
                               )
                            v)
                          (mapcat
                            #(vector
                               (dom/h3 #js {:className "title"} (name (key %)))
                               (dom/div #js {:className "text-body"} (val %)))
                            v)
                          )
                        )
                      (coll? v)
                      (let [ks (-> v first keys)]
                        (dom/table #js {:className "body"}
                          (dom/thead nil
                            (apply dom/tr nil
                              (mapv #(dom/th nil (name %)) ks)))
                          (dom/tfoot nil
                            (apply dom/tr nil
                              (conj
                                (mapv
                                  #(dom/td nil (dom/input #js {:type "text" :ref (name %) :value (get state %) :onChange (fn [e] (handle-change e owner state %))}))
                                  ks)
                                (dom/td nil (dom/button #js {:onClick #(add-collection app owner state ks)} "Add")))
                              ))
                          (apply dom/tbody nil
                            (om/build-all collection-view (k app) {:init-state state :opts k}))
                          )))
                      ])
                 (remove #(re-find #"^_" (-> % key name)) app)))
          (dom/button #js {:className "save" :disabled (:error state) :onClick #(post-save @app)} "Save")
          (dom/button #js {:className "push" :disabled (:error state) :onClick #(push-calendar)} "Push")
          )))))

(defn- iframe-html [id]
  (str "<iframe src='https://www.google.com/calendar/embed?showTitle=0&amp;showPrint=0&amp;showCalendars=0&amp;mode=AGENDA&amp;height=500&amp;wkst=1&amp;bgcolor=%23FFFFFF&amp;src=" id "&amp;color=%23B1440E&amp;ctz=Asia%2FTokyo' style=' border-width:0 ' width='100%' height='500' frameborder='0' scrolling='no'></iframe>"))

(defn- calendar-view [app owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (dom/div #js  {:className (if (not= (:_show app) "CALENDAR") "hide")}
      (dom/h2 #js {:className "title"} "Google Calendar")
        (dom/div #js {:className "body"}
          (dom/div (clj->js {:dangerouslySetInnerHTML {:__html (iframe-html (get-in app [:AUTH :CALENDAR_ID]))}}))
          (let [url (str "https://www.google.com/calendar/embed?src=" (get-in app [:AUTH :CALENDAR_ID]) "&ctz=Asia/Tokyo&mode=AGENDA")]
            (dom/a #js {:href url :target "_blank"} url)))
        (dom/button #js {:className "body push" :onClick #(push-calendar)} "Push")
      ))
    )
  )

(defn- log-view [app owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (dom/div #js  {:className (if (not= (:_show app) "LOG") "hide")}
        (dom/h2 #js {:className "title"} "Push log")
        (dom/div #js {:className "body log"} (dom/textarea #js {:value (:log state)}))
        (dom/button #js {:className "body push" :onClick #(push-calendar)} "Push")
        ))
    om/IDidMount
    (did-mount [this]
      ((fn get-log []
         (GET "/edn/log"
           {:handler (fn [response] (om/set-state! owner :log (:body response)))
            :response-format :edn
            :finally #(.setTimeout js/window get-log 5000)})))
      )
    om/IDidUpdate
    (did-update [this prev-props prev-state]
      (let [elem (first (sel ".log textarea"))]
        (set! (.-scrollTop elem) (.-scrollHeight elem))
        )
      )
    )
  )

(defn- aside [app owner]
  (reify
    om/IRender
    (render [_]
      (apply dom/ul nil
        (map
          #(dom/li nil
             (dom/a #js {:href "#" :className (if (= % (:_show app)) "active")
                         :onClick (fn [e] (om/update! app :_show %) (.preventDefault e))}
               (name %)))
          (:_menu app))
        ))
    ))

(defn- article [app owner]
  (om/component
    (dom/div nil
      (om/build config-edit app)
      (om/build calendar-view app)
      (om/build log-view app)
      )))

(defn init []
  (GET "/edn/config"
    {:handler
     (fn [response]
       (swap! app-state merge response)
       (om/root aside app-state {:target aside-elem})
       (om/root article app-state {:target article-elem})
       )
     :response-format :edn})
  )

(set! (.-onload js/window) init)
