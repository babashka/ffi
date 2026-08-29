(ns portaudio)

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-library {:mac "libportaudio.2.dylib"
                   :linux "libportaudio.so.2"
                   :windows "portaudio.dll"})

(defcfn pa-initialize "Pa_Initialize" [] :int)
(defcfn pa-terminate "Pa_Terminate" [] :int)
(defcfn pa-error-text "Pa_GetErrorText" [:int] :string)
(defcfn pa-open-default-stream "Pa_OpenDefaultStream"
  [:pointer :int :int :ulong :double :ulong :pointer :pointer] :int)
(defcfn pa-start-stream "Pa_StartStream" [:pointer] :int)
(defcfn pa-stop-stream "Pa_StopStream" [:pointer] :int)
(defcfn pa-close-stream "Pa_CloseStream" [:pointer] :int)

(def pa-float32 1)
(def pa-continue 0)
(def pa-output-underflow 0x4)

(def sample-rate 44100.0)
(def frames 256)

(defn check [rc what]
  (when-not (zero? rc)
    (throw (ex-info (str what ": " (pa-error-text rc)) {:rc rc})))
  rc)

;; The note the callback plays. Any thread may write it.
(def freq (atom 440.0))

;; Callback state, allocated once. PortAudio runs the callback on a realtime
;; thread, so it must not allocate. The array hints matter for the same reason:
;; without them aget and aset compile to reflective calls, and reflection
;; resolves classes through the thread context classloader, which a foreign
;; thread does not have.
(def ^double/1 phase (double-array 1))
(def ^float/1 buf (float-array frames))
(def ^long/1 calls (long-array 1))
(def ^long/1 underruns (long-array 1))

(def tau (* 2.0 Math/PI))

(def stream-cb
  (ffi/callback
   (ffi/global-arena)
   (fn [_input output frame-count _time-info status-flags _user-data]
     (let [n (int frame-count)
           step (/ (* tau (double @freq)) sample-rate)]
       (loop [i 0 p (aget phase 0)]
         (if (< i n)
           (do (aset buf i (float (* 0.2 (Math/sin p))))
               (recur (inc i) (let [p (+ p step)]
                                (if (> p tau) (- p tau) p))))
           (aset phase 0 p)))
       (ffi/write-array (ffi/reinterpret output (* n 4)) :float buf)
       (aset calls 0 (inc (aget calls 0)))
       (when-not (zero? (bit-and status-flags pa-output-underflow))
         (aset underruns 0 (inc (aget underruns 0))))
       pa-continue))
   [:pointer :pointer :ulong :pointer :ulong :pointer] :int))

(check (pa-initialize) "Pa_Initialize")

(def stream
  (with-open [arena (ffi/confined-arena)]
    (let [pp (ffi/alloc arena :pointer)]
      (check (pa-open-default-stream pp 0 1 pa-float32 sample-rate frames
                                     stream-cb ffi/null)
             "Pa_OpenDefaultStream")
      (ffi/read pp :pointer))))

(check (pa-start-stream stream) "Pa_StartStream")

;; This thread writes the note. The audio thread reads it.
(doseq [semitone [0 4 7 12 7 4 0 -5]]
  (reset! freq (* 440.0 (Math/pow 2.0 (/ semitone 12.0))))
  (Thread/sleep 350))

(check (pa-stop-stream stream) "Pa_StopStream")
(check (pa-close-stream stream) "Pa_CloseStream")
(check (pa-terminate) "Pa_Terminate")

(println "callbacks:" (aget calls 0))
(println "budget:" (format "%.2f ms" (* 1000.0 (/ frames sample-rate))))
(println "underruns:" (aget underruns 0))
