(ns gtk4)

(require '[babashka.ffi :as ffi :refer [defcfn]])

;; The Windows candidates cover both the MSYS2 and the gvsbuild naming. Put the
;; directory that holds the DLLs on PATH before you start babashka.
(doseq [lib [{:mac "libglib-2.0.0.dylib"
              :linux "libglib-2.0.so.0"
              :windows ["libglib-2.0-0.dll" "glib-2.0-0.dll"]}
             {:mac "libgobject-2.0.0.dylib"
              :linux "libgobject-2.0.so.0"
              :windows ["libgobject-2.0-0.dll" "gobject-2.0-0.dll"]}
             {:mac "libgtk-4.1.dylib"
              :linux "libgtk-4.so.1"
              :windows ["libgtk-4-1.dll" "gtk-4-1.dll"]}]]
  (ffi/load-library lib))

(defcfn g-signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :int] :ulong)

;; gboolean is gint, a 32-bit int, not a one-byte C bool.
(defcfn g-main-loop-new "g_main_loop_new" [:pointer :int] :pointer)
(defcfn g-main-loop-run "g_main_loop_run" [:pointer] :void)
(defcfn g-main-loop-quit "g_main_loop_quit" [:pointer] :void)
(defcfn g-main-loop-unref "g_main_loop_unref" [:pointer] :void)
(defcfn g-idle-add "g_idle_add" [:pointer :pointer] :int)

(defcfn gtk-init "gtk_init" [] :void)
(defcfn gtk-window-new "gtk_window_new" [] :pointer)
(defcfn gtk-window-set-title "gtk_window_set_title" [:pointer :string] :void)
(defcfn gtk-window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(defcfn gtk-window-set-child "gtk_window_set_child" [:pointer :pointer] :void)
(defcfn gtk-window-present "gtk_window_present" [:pointer] :void)
(defcfn gtk-box-new "gtk_box_new" [:int :int] :pointer)
(defcfn gtk-box-append "gtk_box_append" [:pointer :pointer] :void)
(defcfn gtk-label-new "gtk_label_new" [:string] :pointer)
(defcfn gtk-label-set-text "gtk_label_set_text" [:pointer :string] :void)
(defcfn gtk-button-new-with-label "gtk_button_new_with_label" [:string] :pointer)
(defcfn gtk-widget-set-margin-top "gtk_widget_set_margin_top" [:pointer :int] :void)
(defcfn gtk-widget-set-margin-bottom "gtk_widget_set_margin_bottom" [:pointer :int] :void)
(defcfn gtk-widget-set-margin-start "gtk_widget_set_margin_start" [:pointer :int] :void)
(defcfn gtk-widget-set-margin-end "gtk_widget_set_margin_end" [:pointer :int] :void)

(def orientation-vertical 1)

(defn on
  "Connects f to a GTK signal. GTK calls f after this function returns, so the
  callback goes in the global arena."
  ([instance signal arg-types f] (on instance signal arg-types :void f))
  ([instance signal arg-types ret f]
   (let [cb (ffi/callback (ffi/global-arena) f arg-types ret)]
     (g-signal-connect-data instance signal cb ffi/null ffi/null 0))))

(gtk-init)

;; GTK widgets belong to the thread that runs the main loop. run-on-main queues
;; a thunk and asks GLib to drain the queue on that thread. One callback serves
;; every call, because the global arena never frees.
(def pending (atom []))

(def drain
  (ffi/callback (ffi/global-arena)
                (fn [_data]
                  (doseq [f (first (swap-vals! pending empty))] (f))
                  0)
                [:pointer] :int))

(defn run-on-main [f]
  (swap! pending conj f)
  (g-idle-add drain ffi/null))

(def main-loop (g-main-loop-new ffi/null 0))

(def window (gtk-window-new))
(gtk-window-set-title window "babashka.ffi")
(gtk-window-set-default-size window 320 200)

(def box (gtk-box-new orientation-vertical 12))
(doseq [f [gtk-widget-set-margin-top gtk-widget-set-margin-bottom
           gtk-widget-set-margin-start gtk-widget-set-margin-end]]
  (f box 24))

(def clicks-label (gtk-label-new ""))
(def clock-label (gtk-label-new ""))
(def button (gtk-button-new-with-label "Click me"))

(gtk-box-append box clicks-label)
(gtk-box-append box clock-label)
(gtk-box-append box button)
(gtk-window-set-child window box)

(def state (atom {:clicks 0 :time ""}))

(defn render [{:keys [clicks time]}]
  (gtk-label-set-text clicks-label
                      (if (zero? clicks) "no clicks yet" (str clicks " clicks")))
  (gtk-label-set-text clock-label time))

(add-watch state ::render
           (fn [_ _ old new]
             (when (not= old new)
               (run-on-main #(render new)))))

(render @state)

;; void handler (GtkButton *button, gpointer user_data)
(on button "clicked" [:pointer :pointer]
    (fn [_button _data] (swap! state update :clicks inc)))

;; gboolean handler (GtkWindow *window, gpointer user_data). Zero lets the
;; default handler close the window.
(on window "close-request" [:pointer :pointer] :int
    (fn [_window _data] (g-main-loop-quit main-loop) 0))

;; This thread writes to the same atom. The watch marshals the widget call back
;; onto the main loop.
(def clock
  (future
    (while true
      (swap! state assoc :time
             (str (.truncatedTo (java.time.LocalTime/now)
                                java.time.temporal.ChronoUnit/SECONDS)))
      (Thread/sleep 1000))))

(gtk-window-present window)

;; GTK4 has no gtk_main. This blocks and dispatches events until the callback
;; quits the loop.
(g-main-loop-run main-loop)
(future-cancel clock)
(g-main-loop-unref main-loop)

(println "clicks:" (:clicks @state))
