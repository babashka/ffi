;; Pinball in babashka, drawn with raylib through babashka.ffi.
;;
;;   bb pinball.clj [seconds]
;;
;; Z or LEFT flips left, X or RIGHT flips right, R restarts.
;;
;; The physics is TheGeez's scittle pinball, after Ten Minute Physics:
;; https://thegeez.net/2023/03/01/pinball_scittle.html

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "raylib")

(defcfn init-window "InitWindow" [:int :int :string] :void)
(defcfn close-window "CloseWindow" [] :void)
(defcfn window-should-close "WindowShouldClose" [] :uint8)
(defcfn set-target-fps "SetTargetFPS" [:int] :void)
(defcfn begin-drawing "BeginDrawing" [] :void)
(defcfn end-drawing "EndDrawing" [] :void)
(defcfn clear-background "ClearBackground" [:uint] :void)
(defcfn draw-circle "DrawCircle" [:int :int :float :uint] :void)
(defcfn draw-text "DrawText" [:string :int :int :int :uint] :void)
(defcfn screenshot "TakeScreenshot" [:string] :void)
(defcfn key-down? "IsKeyDown" [:int] :uint8)
(defcfn key-pressed? "IsKeyPressed" [:int] :uint8)
(defcfn rl-begin "rlBegin" [:int] :void)
(defcfn rl-end "rlEnd" [] :void)
(defcfn rl-vertex-2f "rlVertex2f" [:float :float] :void)
(defcfn rl-color-4ub "rlColor4ub" [:int :int :int :int] :void)
(defcfn rl-disable-backface-culling "rlDisableBackfaceCulling" [] :void)

(def RL-TRIANGLES 4)
(def KEY-RIGHT 262) (def KEY-LEFT 263)
(def KEY-Z 90) (def KEY-X 88) (def KEY-R 82) (def KEY-PERIOD 46) (def KEY-SLASH 47)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def WHITE (rgba 250 250 250 255))
(def BLACK (rgba 0 0 0 255))
(def GRAY (rgba 128 128 128 255))
(def GREEN [0 153 0])
(def ORANGE [255 128 0])
(def RED [255 0 0])

;; --- geometry ----------------------------------------------------------------
;; The table is 1.0 wide and 1.7 high in simulation units, y up.

(def W 480)
(def H 816)
(def OFFSET 0.02)
(def FLIPPER-HEIGHT 1.7)
(def SCALE (/ H FLIPPER-HEIGHT))

(defn sx [[x _]] (* x SCALE))
(defn sy [[_ y]] (- H (* y SCALE)))

(defn vdot [[ax ay] [bx by]] (+ (* ax bx) (* ay by)))
(defn vlen [[x y]] (Math/sqrt (+ (* x x) (* y y))))
(defn vscale [[x y] s] [(* x s) (* y s)])
(defn vadd [[ax ay] [bx by]] [(+ ax bx) (+ ay by)])
(defn vsub [a b] (vadd a (vscale b -1)))
(defn vperp [[x y]] [(- y) x])
(defn vcross [[ax ay] [bx by]] (- (* ax by) (* ay bx)))

(defn flipper-tip [{:keys [length sign pos rotation rest-angle]}]
  (let [angle (+ rest-angle (* sign rotation))]
    (vadd pos [(* (Math/cos angle) length) (* (Math/sin angle) length)])))

;; --- scene -------------------------------------------------------------------

(defn new-scene []
  (let [ball (fn [pos vel color]
               (let [radius 0.03]
                 {:radius radius :mass (* Math/PI radius radius)
                  :pos pos :vel vel :restitution 0.2 :color color}))
        flipper (fn [id pos rest-angle sign]
                  {:id id :pos pos :radius 0.03 :length 0.2 :rest-angle rest-angle
                   :max-rotation 1.0 :angular-velocity 10.0 :restitution 0.0
                   :sign sign :rotation 0.0 :current-angular-velocity 0.0})]
    {:gravity [0.0 -3.0]
     :dt (/ 1.0 60.0)
     :score 0
     :flippers-active #{}
     :balls [(ball [0.92 0.5] [-0.2 3.5] GRAY)
             (ball [0.08 0.5] [0.2 3.5] BLACK)]
     :border [[0.74 0.25]
              [(- 1.0 OFFSET) 0.4]
              [(- 1.0 OFFSET) (- FLIPPER-HEIGHT OFFSET)]
              [OFFSET (- FLIPPER-HEIGHT OFFSET)]
              [OFFSET 0.4]
              [0.26 0.25]
              [0.26 0.0]
              [0.74 0.0]]
     :obstacles [{:radius 0.1 :pos [0.25 0.6] :push-vel 2.0}
                 {:radius 0.1 :pos [0.75 0.5] :push-vel 2.0}
                 {:radius 0.12 :pos [0.7 1.0] :push-vel 2.0}
                 {:radius 0.1 :pos [0.2 1.2] :push-vel 2.0}]
     :poly-obstacle [[0.50 1.6] [0.60 1.4] [0.55 1.4] [0.52 1.46]
                     [0.50 1.4] [0.45 1.4] [0.49 1.52] [0.45 1.6]]
     :flippers [(flipper :left [0.26 0.22] -0.5 1.0)
                (flipper :right [0.74 0.22] (+ Math/PI 0.5) -1.0)]}))

(def scene (atom (new-scene)))

;; --- physics -----------------------------------------------------------------

(defn simulate-flipper [{:keys [rotation angular-velocity max-rotation sign] :as flipper} dt active?]
  (let [rotation' (if active?
                    (min (+ rotation (* dt angular-velocity)) max-rotation)
                    (max (- rotation (* dt angular-velocity)) 0.0))]
    (assoc flipper
           :rotation rotation'
           :current-angular-velocity (/ (* sign (- rotation' rotation)) dt))))

(defn simulate-ball [ball dt gravity]
  (let [ball (update ball :vel vadd (vscale gravity dt))]
    (update ball :pos vadd (vscale (:vel ball) dt))))

(defn closest-point-on-segment [p a b]
  (let [ab (vsub b a)
        den (vdot ab ab)
        t (if (zero? den) 0.0 (/ (vdot (vsub p a) ab) den))
        t (min 1.0 (max 0.0 t))]
    (vadd a (vscale ab t))))

(defn handle-segments-collision
  "Reflects the ball off the nearest of segments. The segments run counter
  clockwise around the playing area, so the left perpendicular points in."
  [ball segments]
  (if (empty? segments)
    ball
    (let [{ball-pos :pos ball-radius :radius ball-vel :vel restitution :restitution} ball
          [c dist a b] (apply min-key second
                              (map (fn [[a b]]
                                     (let [c (closest-point-on-segment ball-pos a b)]
                                       [c (vlen (vsub ball-pos c)) a b]))
                                   segments))
          cp (vsub ball-pos c)
          [dist cp n] (if (zero? dist)
                        (let [n (vperp (vsub b a))]
                          [0.000001 n (vscale n (/ 1 (vlen n)))])
                        [dist cp (vscale cp (/ 1 dist))])
          ab (vsub b a)
          outside? (neg? (vdot (vperp (vscale ab (/ 1 (vlen ab)))) cp))
          vel (if (or outside? (< dist ball-radius))
                (let [n (if outside? (vscale n -1.0) n)
                      fac (+ 1.0 restitution)]
                  (vsub ball-vel (vscale n (* fac (vdot ball-vel n)))))
                ball-vel)
          pos (cond outside? (vadd ball-pos (vscale n (- (+ ball-radius dist))))
                    (< dist ball-radius) (vsub ball-pos (vscale n (- (- ball-radius dist))))
                    :else ball-pos)]
      (assoc ball :vel vel :pos pos))))

(defn closed-segments [points]
  (partition 2 1 [(first points)] points))

(defn handle-border-collision [ball border]
  (handle-segments-collision ball (closed-segments border)))

(defn orientation [[px py] [qx qy] [rx ry]]
  (let [v (- (* (- qy py) (- rx qx)) (* (- qx px) (- ry qy)))]
    (cond (zero? v) :colinear
          (pos? v) :clockwise
          :else :counterclockwise)))

(defn cross= [o1 o2]
  (or (and (= :clockwise o1) (= :counterclockwise o2))
      (and (= :counterclockwise o1) (= :clockwise o2))))

(defn crossing-segment? [ball [a b]]
  (or (let [c (closest-point-on-segment (:pos ball) a b)
            pos-to-c (vsub (:pos ball) c)]
        (and (< (vlen pos-to-c) (:radius ball))
             (<= 0.0 (vdot pos-to-c (vperp (vsub b a))))))
      (when-let [c (:last-vel-change-pos ball)]
        (let [d (:pos ball)]
          (and (cross= (orientation a b c) (orientation a b d))
               (cross= (orientation c d a) (orientation c d b)))))))

(defn distance-to-cross-line [[p pr] [q qs]]
  (let [r (vsub pr p)
        s (vsub qs q)]
    (vcross (vsub p q) (vscale r (/ 1.0 (vcross s r))))))

(defn handle-poly-obstacle-collision [ball points]
  (let [crossing (filter #(crossing-segment? ball %) (closed-segments points))]
    (if (seq crossing)
      (let [ball-line [(:last-vel-change-pos ball) (:pos ball)]
            nearest (apply min-key #(distance-to-cross-line % ball-line) crossing)]
        (-> (assoc ball :restitution 0.6)
            (handle-segments-collision [nearest])
            (update :points (fnil + 0) 5)))
      ball)))

(defn handle-obstacle-collision [ball {obs-pos :pos obs-radius :radius push-vel :push-vel}]
  (let [{ball-pos :pos ball-radius :radius ball-vel :vel} ball
        dir (vsub ball-pos obs-pos)
        dist (vlen dir)]
    (if (< (+ ball-radius obs-radius) dist)
      ball
      (let [n (vscale dir (/ 1.0 dist))
            corr (- (+ ball-radius obs-radius) dist)
            v (vdot ball-vel n)]
        (-> ball
            (assoc :pos (vadd ball-pos (vscale n corr))
                   :vel (vadd ball-vel (vscale n (- push-vel v))))
            (update :points (fnil + 0) 1))))))

(defn handle-ball-collision [ball-i ball-j]
  (let [restitution (min (:restitution ball-i) (:restitution ball-j))
        dir (vsub (:pos ball-j) (:pos ball-i))
        dist (vlen dir)]
    (if (or (== dist 0.0) (>= dist (+ (:radius ball-i) (:radius ball-j))))
      [ball-i ball-j]
      (let [n (vscale dir (/ 1.0 dist))
            corr (/ (- (+ (:radius ball-i) (:radius ball-j)) dist) 2.0)
            ball-i (update ball-i :pos vadd (vscale n (- corr)))
            ball-j (update ball-j :pos vadd (vscale n corr))
            v1 (vdot (:vel ball-i) n)
            v2 (vdot (:vel ball-j) n)
            m1 (:mass ball-i)
            m2 (:mass ball-j)
            momentum (+ (* m1 v1) (* m2 v2))
            v1' (/ (- momentum (* m2 (- v1 v2) restitution)) (+ m1 m2))
            v2' (/ (- momentum (* m1 (- v2 v1) restitution)) (+ m1 m2))]
        [(update ball-i :vel vadd (vscale n (- v1' v1)))
         (update ball-j :vel vadd (vscale n (- v2' v2)))]))))

(defn handle-flipper-collision [ball flipper]
  (let [{flipper-pos :pos flipper-radius :radius omega :current-angular-velocity} flipper
        {ball-pos :pos ball-radius :radius ball-vel :vel} ball
        closest (closest-point-on-segment ball-pos flipper-pos (flipper-tip flipper))
        dir (vsub ball-pos closest)
        dist (vlen dir)]
    (if (< (+ ball-radius flipper-radius) dist)
      ball
      (let [n (vscale dir (/ 1.0 dist))
            pos-corr (vscale n (- (+ ball-radius flipper-radius) dist))
            contact (vadd closest (vscale n flipper-radius))
            surface-vel (vscale (vperp (vsub contact flipper-pos)) omega)
            v (vdot ball-vel n)
            v' (vdot surface-vel n)]
        (-> ball
            (update :pos vadd pos-corr)
            (update :vel vadd (vscale n (- v' v))))))))

(defn handle-obstacle-collision-all [ball obstacles]
  (reduce handle-obstacle-collision ball obstacles))

(defn simulate-balls [{:keys [dt gravity balls border obstacles flippers poly-obstacle]}]
  (reduce
   (fn [balls i]
     (let [old-vel (get-in balls [i :vel])
           balls (update balls i simulate-ball dt gravity)
           balls (reduce (fn [balls j]
                           (let [[bi bj] (handle-ball-collision (get balls i) (get balls j))]
                             (assoc balls i bi j bj)))
                         balls
                         (range (inc i) (count balls)))
           balls (-> balls
                     (update i handle-obstacle-collision-all obstacles)
                     (update i handle-poly-obstacle-collision poly-obstacle)
                     (update i #(reduce handle-flipper-collision % flippers))
                     (update i handle-border-collision border))]
       (if (not= old-vel (get-in balls [i :vel]))
         (assoc-in balls [i :last-vel-change-pos] (get-in balls [i :pos]))
         balls)))
   balls
   (range (count balls))))

(defn step [{:keys [dt flippers-active] :as ps}]
  (let [ps (update ps :flippers
                   (fn [flippers]
                     (mapv #(simulate-flipper % dt (contains? flippers-active (:id %))) flippers)))
        balls (simulate-balls ps)
        points (reduce + 0 (keep :points balls))]
    (-> ps
        (assoc :balls (mapv #(dissoc % :points) balls))
        (update :score + points))))

;; --- drawing -----------------------------------------------------------------

(defn line! [p0 p1 width [r g b]]
  ;; a quad along the segment, in rlgl so no Vector2 is needed
  (let [d (vsub p1 p0)
        n (vscale (vperp d) (/ (* 0.5 width) (vlen d)))
        [ax ay] (vadd p0 n) [bx by] (vadd p1 n)
        [cx cy] (vsub p1 n) [dx dy] (vsub p0 n)]
    (rl-begin RL-TRIANGLES)
    (rl-color-4ub r g b 255)
    (rl-vertex-2f ax ay) (rl-vertex-2f bx by) (rl-vertex-2f cx cy)
    (rl-vertex-2f ax ay) (rl-vertex-2f cx cy) (rl-vertex-2f dx dy)
    (rl-end)))

(defn polyline! [points width color]
  (doseq [[a b] (closed-segments points)]
    (line! [(sx a) (sy a)] [(sx b) (sy b)] width color)))

(defn disc! [p radius color]
  (draw-circle (long (sx p)) (long (sy p)) (* radius SCALE) color))

(defn draw! [{:keys [border poly-obstacle balls obstacles flippers score]}]
  (clear-background WHITE)
  (polyline! border 5 GREEN)
  (polyline! poly-obstacle 5 ORANGE)
  (doseq [b balls] (disc! (:pos b) (:radius b) (:color b)))
  (doseq [o obstacles] (disc! (:pos o) (:radius o) (apply rgba (conj ORANGE 255))))
  (doseq [f flippers]
    (let [tip (flipper-tip f)
          red (apply rgba (conj RED 255))]
      (line! [(sx (:pos f)) (sy (:pos f))] [(sx tip) (sy tip)] (* 2 (:radius f) SCALE) RED)
      (disc! (:pos f) (:radius f) red)
      (disc! tip (:radius f) red)))
  (draw-text (str "score: " score) 10 10 24 BLACK))

;; --- input and main ----------------------------------------------------------

(defn down? [& ks] (some #(pos? (key-down? %)) ks))

(defn read-input! []
  (swap! scene assoc :flippers-active
         (cond-> #{}
           (down? KEY-Z KEY-LEFT KEY-PERIOD) (conj :left)
           (down? KEY-X KEY-RIGHT KEY-SLASH) (conj :right)))
  (when (pos? (key-pressed? KEY-R))
    (reset! scene (new-scene))))

(def deadline
  (when-let [s (first *command-line-args*)]
    (+ (System/currentTimeMillis) (* 1000 (parse-long s)))))

(def frame (atom 0))

(defn -main []
  (init-window W H "babashka.ffi - pinball")
  (set-target-fps 60)
  (rl-disable-backface-culling)
  (while (and (zero? (window-should-close))
              (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (read-input!)
    (swap! scene step)
    (begin-drawing)
    (draw! @scene)
    (end-drawing)
    (swap! frame inc)
    (when (and (System/getenv "SHOT")
               (= (parse-long (System/getenv "SHOT")) @frame))
      (screenshot "pinball.png")))
  (close-window)
  (println "final score:" (:score @scene)))

(when-not (System/getenv "HEADLESS") (-main))
