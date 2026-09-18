(ns babashka.ffi.impl.libffi-version
  "The version of the libffi linked into a native image. A namespace of its
  own, which babashka.ffi never loads: ffi_get_version arrived in libffi 3.5,
  and an image that links an older libffi must not reach it."
  {:no-doc true}
  (:import [babashka.ffi.impl Libffi]))

(set! *warn-on-reflection* true)

(defn version
  "Returns the version of the linked libffi."
  []
  (Libffi/version))
