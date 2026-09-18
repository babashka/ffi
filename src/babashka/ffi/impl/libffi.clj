(ns babashka.ffi.impl.libffi
  "Calls the libffi that is linked into a native image, through the
  @CFunction class Libffi. babashka.ffi loads this namespace only in an image
  built with BABASHKA_FEATURE_LIBFFI=true. Loading it in an image that does
  not link libffi fails at link time."
  {:no-doc true}
  (:import [babashka.ffi.impl Libffi]))

(set! *warn-on-reflection* true)

(defn version
  "Returns the version of the linked libffi."
  []
  (Libffi/version))

(defn prep-cif
  "Calls ffi_prep_cif and returns its status code."
  [cif abi nargs rtype atypes]
  (Libffi/prepCif (long cif) (int abi) (int nargs) (long rtype) (long atypes)))

(defn prep-cif-var
  "Calls ffi_prep_cif_var, for a variadic call, and returns its status code."
  [cif abi nfixed ntotal rtype atypes]
  (Libffi/prepCifVar (long cif) (int abi) (int nfixed) (int ntotal) (long rtype) (long atypes)))

(defn call
  "Calls ffi_call and writes the return value to rvalue."
  [cif fnp rvalue avalues]
  (Libffi/call (long cif) (long fnp) (long rvalue) (long avalues)))
