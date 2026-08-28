;; libffi bound through babashka.ffi itself: ffi_prep_cif and ffi_call are
;; plain pointer/int signatures, so the bounded FFI can bootstrap an
;; unbounded one. Demonstrates a struct-by-value RETURN (libc div_t div(int,
;; int)), impossible with scalar bindings, and benchmarks a libffi call
;; against the trampoline path.
;;
;; Every allocation lives in an arena that closes after use, also when the
;; body throws. The last section compares an arena per iteration with one
;; arena reused across the loop.
;;
;;   bb examples/libffi.clj

(ns libffi)

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "ffi")

(defcfn prep-cif "ffi_prep_cif" [:pointer :int :uint :pointer :pointer] :int)
(defcfn ffi-call "ffi_call" [:pointer :pointer :pointer :pointer] :void)
(defcfn c-dlsym "dlsym" [:pointer :string] :pointer)

(def RTLD-DEFAULT
  ;; This pseudo handle is not a memory address.
  (ffi/segment (if (= "Mac OS X" (System/getProperty "os.name")) -2 0)))

(defn sym-addr [name]
  (let [p (c-dlsym RTLD-DEFAULT name)]
    (when (ffi/null? p)
      (throw (ex-info (str "symbol not found: " name) {})))
    p))

;; ffi_type: {size_t size; unsigned short alignment; unsigned short type;
;;            ffi_type **elements} - 24 bytes on 64-bit
(defn ffi-type [arena size align code elements]
  (let [t (ffi/alloc arena 24)]
    (ffi/write t :size_t size 0)
    (ffi/write t :uint16 align 8)
    (ffi/write t :uint16 code 10)
    (ffi/write t :pointer elements 16)
    t))

(defn struct-type
  "Returns an FFI_TYPE_STRUCT for element-types.
  prep_cif sets its size and alignment."
  [arena element-types]
  (let [elems (ffi/alloc arena (* 8 (inc (count element-types))))]
    (doseq [[i t] (map-indexed vector element-types)]
      (ffi/write elems :pointer t (* 8 i)))
    (ffi/write elems :pointer ffi/null (* 8 (count element-types)))
    (ffi-type arena 0 0 13 elems)))

(def FFI-DEFAULT-ABI
  ;; On aarch64, FFI_SYSV is 1. On x86-64, FFI_UNIX64 is 2.
  (if (= "aarch64" (System/getProperty "os.arch")) 1 2))

(defn make-cif [arena ret-type arg-types]
  (let [n (count arg-types)
        atypes (ffi/alloc arena (max 8 (* 8 n)))
        cif (ffi/alloc arena 128)]
    (doseq [[i t] (map-indexed vector arg-types)]
      (ffi/write atypes :pointer t (* 8 i)))
    (when-not (zero? (prep-cif cif FFI-DEFAULT-ABI n ret-type atypes))
      (throw (ex-info "ffi_prep_cif failed" {})))
    cif))

;; -- struct-by-value return: div_t div(int, int) ------------------------------

;; The arena owns each allocation in this form. This includes allocations in
;; struct-type and make-cif.
(with-open [arena (ffi/confined-arena)]
  (let [t-sint32 (ffi-type arena 4 4 10 nil)
        t-div (struct-type arena [t-sint32 t-sint32])
        cif (make-cif arena t-div [t-sint32 t-sint32])
        fnp (sym-addr "div")
        a0 (ffi/alloc arena :int)
        a1 (ffi/alloc arena :int)
        avalues (ffi/alloc arena 16)
        rvalue (ffi/alloc arena 8)]
    (ffi/write avalues :pointer a0 0)
    (ffi/write avalues :pointer a1 8)
    (ffi/write a0 :int 7 0)
    (ffi/write a1 :int 2 0)
    (ffi-call cif fnp rvalue avalues)
    (println "div(7, 2) =" {:quot (ffi/read rvalue :int 0)
                            :rem (ffi/read rvalue :int 4)}
             (if (= [3 1] [(ffi/read rvalue :int 0) (ffi/read rvalue :int 4)])
               "OK" "FAIL"))))

;; -- Benchmark: Compare ldexp through libffi and cfn. -------------------------

(def N 200000)

(with-open [arena (ffi/confined-arena)]
  (let [t-double (ffi-type arena 8 8 3 nil)
        t-sint32 (ffi-type arena 4 4 10 nil)
        cif (make-cif arena t-double [t-double t-sint32])
        fnp (sym-addr "ldexp")
        a0 (ffi/alloc arena :double)
        a1 (ffi/alloc arena :int)
        avalues (ffi/alloc arena 16)
        rvalue (ffi/alloc arena 8)]
    (ffi/write avalues :pointer a0 0)
    (ffi/write avalues :pointer a1 8)
    (ffi/write a0 :double 1.5 0)
    (ffi/write a1 :int 3 0)
    (ffi-call cif fnp rvalue avalues)
    (println "ldexp via libffi =" (ffi/read rvalue :double 0))
    (let [t0 (System/nanoTime)]
      (loop [i 0]
        (when (< i N)
          (ffi/write a0 :double 1.5 0)
          (ffi/write a1 :int i 0)
          (ffi-call cif fnp rvalue avalues)
          (ffi/read rvalue :double 0)
          (recur (inc i))))
      (println "libffi:    " (quot (- (System/nanoTime) t0) N) "ns/call"))))

(let [ldexp (ffi/cfn "ldexp" [:double :int] :double)]
  (ldexp 1.5 3)
  (let [t0 (System/nanoTime)]
    (loop [i 0]
      (when (< i N)
        (ldexp 1.5 i)
        (recur (inc i))))
    (println "trampoline:" (quot (- (System/nanoTime) t0) N) "ns/call")))

;; -- Measure the cost of an arena. --------------------------------------------

(let [t0 (System/nanoTime)]
  (loop [i 0]
    (when (< i 50000)
      (with-open [arena (ffi/confined-arena)]
        (let [p (ffi/alloc arena :int)]
          (ffi/write p :int i)
          (ffi/read p :int)))
      (recur (inc i))))
  (println "arena per iteration:" (quot (- (System/nanoTime) t0) 50000) "ns"))

;; The same work with one arena for the whole loop. Every allocation belongs
;; to an arena, so the choice is not whether to have one but how wide to
;; scope it.
(with-open [arena (ffi/confined-arena)]
  (let [t0 (System/nanoTime)]
    (loop [i 0]
      (when (< i 50000)
        (let [p (ffi/alloc arena :int)]
          (ffi/write p :int i)
          (ffi/read p :int))
        (recur (inc i))))
    (println "one arena, reused:  " (quot (- (System/nanoTime) t0) 50000) "ns")))

(println "LIBFFI OK")