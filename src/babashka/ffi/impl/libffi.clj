(ns babashka.ffi.impl.libffi
  "Calls the libffi that is linked into a native image, through the
  @CFunction class Libffi. babashka.ffi loads this namespace only in an image
  built with BABASHKA_FEATURE_LIBFFI=true. Loading it in an image that does
  not link libffi fails at link time."
  {:no-doc true}
  (:import [babashka.ffi.impl Libffi LibffiClosure]))

(set! *warn-on-reflection* true)

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

(defn closure-alloc
  "Calls ffi_closure_alloc and returns the writable address of the closure.
  Writes the executable address to the pointer at code."
  [size code]
  (Libffi/closureAlloc (long size) (long code)))

(defn prep-closure-loc
  "Calls ffi_prep_closure_loc and returns its status code."
  [closure cif fun user-data codeloc]
  (Libffi/prepClosureLoc (long closure) (long cif) (long fun) (long user-data) (long codeloc)))

(defn closure-register
  "Stores a closure for the handler in LibffiClosure and returns the index
  the closure passes as its user data. See LibffiClosure/register for the
  arguments."
  [f kinds decs sizes ret-kind ret ret-size]
  (LibffiClosure/register f kinds decs sizes (int ret-kind) ret (long ret-size)))

(defn closure-unregister
  "Frees the index i that closure-register returned."
  [i]
  (LibffiClosure/unregister (long i)))

(defn closure-handler
  "Returns the address of the upcall stub that every closure calls."
  []
  (LibffiClosure/handler))

(defn closure-free
  "Calls ffi_closure_free."
  [closure]
  (Libffi/closureFree (long closure)))
