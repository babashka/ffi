;; A paint program in babashka, drawn with SDL3 through babashka.ffi.
;;
;;   bb examples/sdl3.cljc [seconds]
;;   nbb --classpath src examples/sdl3.cljc [seconds]
;;
;; Drag to paint. Click a swatch or press 1 to 6 to pick a color, C clears,
;; ESC or the window button quits. An optional argument limits the run to
;; that many seconds.
;;
;; Input arrives in SDL_Event, the 128-byte union SDL_PollEvent fills. The
;; layout below mirrors SDL_events.h, and each field is read through a place
;; made once, so the loop computes no offsets.
;;
;; Needs SDL3: brew install sdl3, or the SDL3 package of your distribution.
;; On macOS the JVM run also needs -XstartOnFirstThread, because SDL uses
;; thread 0 for the Cocoa event loop.

(ns sdl3)

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "SDL3")

;; SDL_InitFlags is Uint32, SDL_WindowFlags is Uint64.
(defcfn sdl-init "SDL_Init" [:uint32] :bool)
(defcfn sdl-quit "SDL_Quit" [] :void)
(defcfn get-error "SDL_GetError" [] :string)
(defcfn get-ticks "SDL_GetTicks" [] :uint64)
(defcfn create-window "SDL_CreateWindow" [:string :int :int :uint64] :pointer)
(defcfn set-window-minimum-size "SDL_SetWindowMinimumSize" [:pointer :int :int] :bool)
(defcfn destroy-window "SDL_DestroyWindow" [:pointer] :void)
(defcfn create-renderer "SDL_CreateRenderer" [:pointer :string] :pointer)
(defcfn destroy-renderer "SDL_DestroyRenderer" [:pointer] :void)
(defcfn set-render-vsync "SDL_SetRenderVSync" [:pointer :int] :bool)
(defcfn poll-event "SDL_PollEvent" [:pointer] :bool)
(defcfn set-draw-color "SDL_SetRenderDrawColor"
  [:pointer :uint8 :uint8 :uint8 :uint8] :bool)
(defcfn render-clear "SDL_RenderClear" [:pointer] :bool)
(defcfn render-line "SDL_RenderLine" [:pointer :float :float :float :float] :bool)
(defcfn render-fill-rect "SDL_RenderFillRect" [:pointer :pointer] :bool)
(defcfn render-debug-text "SDL_RenderDebugText" [:pointer :float :float :string] :bool)
(defcfn set-render-scale "SDL_SetRenderScale" [:pointer :float :float] :bool)
(defcfn render-present "SDL_RenderPresent" [:pointer] :bool)

(def SDL-INIT-VIDEO 0x20)
(def SDL-WINDOW-RESIZABLE 0x20)

(def EVENT-QUIT 0x100)
(def EVENT-WINDOW-RESIZED 0x206)
(def EVENT-KEY-DOWN 0x300)
(def EVENT-MOUSE-MOTION 0x400)
(def EVENT-MOUSE-BUTTON-DOWN 0x401)
(def EVENT-MOUSE-BUTTON-UP 0x402)

(def SDLK-ESCAPE 0x1b)
(def SDLK-C 0x63)
(def SDLK-1 0x31)

(def BUTTON-LEFT 1)

;; SDL_Event from SDL_events.h, with the members this program reads. The
;; padding member fixes the size at 128 bytes, as it does in C.
(def sdl-event
  [:union
   [[:type :uint32]
    [:key [:struct [[:type :uint32]
                    [:reserved :uint32]
                    [:timestamp :uint64]
                    [:window-id :uint32]
                    [:which :uint32]
                    [:scancode :uint32]
                    [:key :uint32]
                    [:mod :uint16]
                    [:raw :uint16]
                    [:down :bool]
                    [:repeat :bool]]]]
    [:window [:struct [[:type :uint32]
                       [:reserved :uint32]
                       [:timestamp :uint64]
                       [:window-id :uint32]
                       [:data1 :int32]
                       [:data2 :int32]]]]
    [:motion [:struct [[:type :uint32]
                       [:reserved :uint32]
                       [:timestamp :uint64]
                       [:window-id :uint32]
                       [:which :uint32]
                       [:state :uint32]
                       [:x :float]
                       [:y :float]
                       [:xrel :float]
                       [:yrel :float]]]]
    [:button [:struct [[:type :uint32]
                       [:reserved :uint32]
                       [:timestamp :uint64]
                       [:window-id :uint32]
                       [:which :uint32]
                       [:button :uint8]
                       [:down :bool]
                       [:clicks :uint8]
                       [:padding :uint8]
                       [:x :float]
                       [:y :float]]]]
    [:padding [:array :uint8 128]]]])

(def ev-type (ffi/place sdl-event :type))
(def ev-key (ffi/place sdl-event [:key :key]))
(def ev-repeat (ffi/place sdl-event [:key :repeat]))
(def ev-win-w (ffi/place sdl-event [:window :data1]))
(def ev-win-h (ffi/place sdl-event [:window :data2]))
(def ev-motion-x (ffi/place sdl-event [:motion :x]))
(def ev-motion-y (ffi/place sdl-event [:motion :y]))
(def ev-button (ffi/place sdl-event [:button :button]))
(def ev-button-x (ffi/place sdl-event [:button :x]))
(def ev-button-y (ffi/place sdl-event [:button :y]))

(def frect [:struct [[:x :float] [:y :float] [:w :float] [:h :float]]])

(def W 900)
(def H 600)

;; the narrowest window the HUD still fits in at the scale draw! uses
(def MIN-W 560)
(def MIN-H 320)

(def palette
  [[235 64 52] [235 158 52] [222 222 52] [52 186 91] [52 137 235] [158 52 235]])

(def state (atom {:strokes [] :current nil :color 0 :last "" :quit false :w W :h H}))

;; --- events ------------------------------------------------------------------

(defn pick-color [s i]
  (assoc s :color i :last (str "color " (inc i))))

(defn on-key [k]
  (cond
    (= k SDLK-ESCAPE) (swap! state assoc :quit true :last "ESC")
    (= k SDLK-C) (swap! state assoc :strokes [] :current nil :last "clear")
    (< (dec SDLK-1) k (+ SDLK-1 (count palette)))
    (swap! state pick-color (- k SDLK-1))
    :else (swap! state assoc :last (str "key " k))))

(defn swatch-at
  "Returns the palette index under x y in a window h pixels tall, or nil."
  [h x y]
  (when (and (<= 10 x) (<= (- h 34) y (- h 10)))
    (let [i (long (quot (- x 10) 34))]
      (when (and (< i (count palette)) (<= (- x 10 (* i 34)) 28))
        i))))

(defn start-stroke [s x y]
  (assoc s :current {:color (:color s) :points [x y]} :last "down"))

(defn press [s x y]
  (if-let [i (swatch-at (:h s) x y)]
    (pick-color s i)
    (start-stroke s x y)))

(defn extend-stroke [s x y]
  (if (:current s)
    (assoc s :current (update (:current s) :points conj x y) :last "drag")
    (assoc s :last "move")))

(defn end-stroke [s]
  (if-let [c (:current s)]
    (assoc s :strokes (conj (:strokes s) c) :current nil :last "up")
    s))

(defn handle-event! [ev]
  (let [t (ffi/read ev ev-type)]
    (cond
      (= t EVENT-QUIT)
      (swap! state assoc :quit true :last "quit")

      (= t EVENT-WINDOW-RESIZED)
      (let [w (ffi/read ev ev-win-w) h (ffi/read ev ev-win-h)]
        (swap! state assoc :w w :h h :last (str "resize " w "x" h)))

      (= t EVENT-KEY-DOWN)
      (when-not (ffi/read ev ev-repeat)
        (on-key (ffi/read ev ev-key)))

      (= t EVENT-MOUSE-MOTION)
      (swap! state extend-stroke (ffi/read ev ev-motion-x) (ffi/read ev ev-motion-y))

      (= t EVENT-MOUSE-BUTTON-DOWN)
      (when (= BUTTON-LEFT (ffi/read ev ev-button))
        (swap! state press (ffi/read ev ev-button-x) (ffi/read ev ev-button-y)))

      (= t EVENT-MOUSE-BUTTON-UP)
      (swap! state end-stroke))))

;; --- drawing -----------------------------------------------------------------

(defn stroke! [ren {:keys [color points]}]
  (let [[r g b] (palette color)
        n (count points)]
    (set-draw-color ren r g b 255)
    (loop [i 2]
      (when (< i n)
        (render-line ren (nth points (- i 2)) (nth points (- i 1))
                     (nth points i) (nth points (inc i)))
        (recur (+ i 2))))))

(defn swatches! [ren rect h selected]
  (dotimes [i (count palette)]
    (let [[r g b] (palette i)
          x (+ 10 (* i 34))
          sel (= i selected)]
      (ffi/write rect frect {:x x :y (- h 34) :w 28 :h 24})
      (set-draw-color ren r g b 255)
      (render-fill-rect ren rect)
      (when sel
        (set-draw-color ren 255 255 255 255)
        (render-line ren x (- h 38) (+ x 28) (- h 38))))))

(defn draw! [ren rect {:keys [strokes current color last h]}]
  (set-draw-color ren 24 24 32 255)
  (render-clear ren)
  (doseq [s strokes] (stroke! ren s))
  (when current (stroke! ren current))
  (swatches! ren rect h color)
  (set-draw-color ren 200 200 210 255)
  ;; SDL_RenderDebugText draws an 8 pixel font, so scale it and halve the coordinates.
  (set-render-scale ren 2.0 2.0)
  (render-debug-text ren 5.0 5.0 (str "strokes " (count strokes) "   last " last))
  (render-debug-text ren 5.0 21.0 "drag to paint, C clear, ESC quit")
  (render-debug-text ren 112.0 (/ (- h 30) 2.0) "click or press 1-6")
  (set-render-scale ren 1.0 1.0)
  (render-present ren))

;; --- main --------------------------------------------------------------------

(defn -main []
  (when-not (sdl-init SDL-INIT-VIDEO)
    (throw (ex-info (str "SDL_Init: " (get-error)) {})))
  (let [win (create-window "babashka.ffi - sdl3 paint" W H SDL-WINDOW-RESIZABLE)
        _ (when (ffi/null? win) (throw (ex-info (str "SDL_CreateWindow: " (get-error)) {})))
        ren (create-renderer win nil)
        _ (when (ffi/null? ren) (throw (ex-info (str "SDL_CreateRenderer: " (get-error)) {})))
        deadline (when-let [s (first *command-line-args*)]
                   (+ (get-ticks) (* 1000 (parse-long s))))]
    (set-window-minimum-size win MIN-W MIN-H)
    (set-render-vsync ren 1)
    (#?(:clj with-open :cljs ffi/with-open) [arena (ffi/confined-arena)]
      (let [ev (ffi/alloc arena sdl-event)
            rect (ffi/alloc arena frect)]
        (while (and (not (:quit @state))
                    (or (nil? deadline) (< (get-ticks) deadline)))
          (while (poll-event ev)
            (handle-event! ev))
          (draw! ren rect @state))))
    (destroy-renderer ren)
    (destroy-window win)
    (sdl-quit)
    (println "strokes:" (count (:strokes @state)))))

(when-not #?(:clj (System/getenv "HEADLESS") :cljs (unchecked-get js/process.env "HEADLESS"))
  (-main))
