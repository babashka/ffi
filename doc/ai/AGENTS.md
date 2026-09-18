# babashka.ffi for agents

Read this before touching the code. The user guide is doc/guide.md, the
API listing is API.md, and the decisions are in doc/ai/adr/.

## Layout

- src/babashka/ffi.clj: the whole public API in one namespace. Babashka
  embeds it as a built-in, so it stays one file and depends only on the JDK.
- src/babashka/ffi/impl/binding.clj: the JVM downcall path, a hidden class
  per binding generated with the Class-File API. Loaded from ffi.clj with
  requiring-resolve on a quoted symbol, never in a native image. Keep it
  that way: a static require would pull it into the babashka binary. It
  receives the ffi.clj helpers it needs in a map, so it has no requires and
  loads in any order.
- src/babashka/ffi.cljs: the same API on Node.js through node:ffi, for nbb,
  ClojureScript and shadow-cljs. A separate file, so ffi.clj stays JDK only.
  It shares no code with ffi.clj: a change to a layout rule, a type keyword
  or an error message goes in both. The ClojureScript compiler takes defcfn
  and with-open from ffi.clj, and nbb takes them from the defmacros in
  ffi.cljs, so a macro change goes in both as well. See ADR 0008.
- resources/clj-kondo.exports: the defcfn hook.
- test/babashka/ffi_test.clj: one suite for both hosts.
- test-jvm/babashka/ffi_binding_test.clj: the generated class, JVM only.
- test-node/babashka/ffi_test.cljs: the Node.js suite. It follows
  ffi_test.clj case by case, without what node:ffi cannot call.
- test-native/babashka/ffi/native_test.clj: what only a native image
  decides, the trampolines and the registered upcall shapes. Plain
  assertions, no framework, built and run by script/native_test.clj.
- script/gen_ffi_metadata.clj: generates the trampolines and the
  reachability metadata into src, src-java and resources, all committed.
  babashka builds them straight from here, so the shape set and the code
  that assumes it live in one place. Regenerate and commit after changing
  the generator; metadata-generated-test fails otherwise. There are two
  trampoline sets, sorted and ordered, and ffi.clj loads the ordered one on
  Windows when the image is built, so no build runs the generator. The 39
  upcall shapes only Windows needs are in
  resources/babashka/ffi/native-image-windows, which native-image reads only
  when -H:ConfigurationResourceRoots names it. An image elsewhere is the same
  size with or without them, measured: 16,340,144 bytes against 16,389,824
  with the flag. GraalVM 25 refuses a condition on a foreign call, which
  would have kept this to one file.
- test-resources/struct_lib.c: fixture for struct-by-value tests, compiled
  into target/ when cc or cl is on PATH.
- examples/: runnable scripts, each on both hosts.

Babashka consumes this repository as a git submodule. In this checkout .git
is a file, and worktrees live under babashka's .git/modules/ffi.

## Hosts and call paths

cfn picks the path once, when the binding is made. The binding's metadata
names it as :babashka.ffi/backend.

| Signature | JVM | Native image |
|---|---|---|
| fixed, scalars, up to 20 args | binding.clj generated class with the FFM handle as a constant, 3 to 6 ns, about 70 us to create | compiled trampoline when the shape is in the set, about 30 ns, else libffi, about 1 us |
| struct by value | generated class, a segment per struct and an allocator slot for a struct return, about 70 ns | libffi |
| variadic, tail inferred per call | cached binding per tail shape, about 55 ns more than a declared tail | libffi |
| variadic, tail declared | generated class with firstVariadicArg | libffi |
| more than 20 args, fixed or variadic | FFM handle with invokeWithArguments | libffi |

Every type keyword has a carrier: :long, :double, :float or :void. The
A trampoline takes every argument as a long, which is not the width C gives
a narrow integer. That shows only once an argument reaches the stack, and
only where the ABI packs a stack slot to the width of the argument, which
macOS on AArch64 does. The shape set is the same everywhere, so the
generated sources are too and can be committed; trampoline-id declines a
shape with a narrow type past the eighth argument at run time instead, and
the call goes to libffi. apple-aarch64? and narrow-on-stack? hold that rule.
It reads os.arch when the image is built, so it does not survive a cross
build.

trampoline set and the generated class bytes are keyed on carriers, not
types. The generated class passes every argument and result as a long,
doubles and floats as raw bits, and resolves the symbol on the first call
through a MutableCallSite.

Callbacks use FFM upcall stubs on both hosts and keep the limits listed in
doc/guide.md under Callbacks.

## Type tables

Adding or changing a type keyword touches each of these. Keep them in sync.

- ffi.clj long-carrier? and carrier
- ffi.clj arg-coercer, one fn per type, chosen at binding time
- ffi.clj narrow-ret, the return conversion for the boxed paths
- binding.clj bits-ret-fn, the same table over raw long bits for the JVM path
- ffi.clj sizes, array-carriers, signature-layout, exact-layout, ffi-type-codes
- the case tables in read, write and place
- the callback return coercion in callback

jvm-return-conversion-test checks narrow-ret and bits-ret-fn against each
other through a callback that returns each type.

signature-layout is what a descriptor names a type by, at the width C gives
it. return-layout is the same for a return value, except that :bool is read
as an int: a C predicate such as isalpha returns an int whose low byte can be
zero, 1024 on glibc, and only Linux shows it. A carrier is what the call path moves it in. The two differ for every
integer narrower than 64 bits, so a handle built from a descriptor is cast
between them: carrier-handle for the generic invoker, struct-handle in
binding.clj for the generated class, and explicitCastArguments onto
signature-method-type for an upcall stub. Naming a narrow integer by its
carrier reads the wrong bytes once arguments spill to the stack, which
stack-arguments-test covers.

A callback in a native image is the exception: it keeps the carrier shape,
through carrier-descriptor, and narrows each value on arrival instead, in
the in-c table in callback. babashka registers the upcall shapes an image
can make when it builds it, from script/gen_ffi_metadata.clj, and one shape
per width per position is not a set anything can register. The narrowing is
what makes that sound, not the six-argument cap: a C caller writes the low
half of the register and leaves the upper half zero, so a narrow integer
read at its carrier width arrives without its sign, in a register as much
as on the stack. narrow-int? lists the types this applies to, and
narrow-ret does the conversion.

## Run the tests

JVM, needs JDK 25 or newer, the alias enables native access:

```sh
clojure -M:test
```

Babashka, through its built-in copy of this namespace:

```sh
bb test:bb
```

A native image, needs GRAALVM_HOME and a C compiler. This is the only run
that exercises the branch's code on the trampolines and the upcall shapes an
image registers. Running the suite against a released babashka does not:
that binary carries the babashka.ffi it was built with, so `bb test:bb`
reports on babashka's code, not on the tree.

```sh
bb test:native
```

Node.js, needs Node.js 26.1 or newer on PATH. The three commands run
test-node under nbb, ClojureScript and shadow-cljs. The last two need JDK 25
or newer:

```sh
bb test:node
bb test:cljs
bb test:shadow
```

In ffi.cljs, hint a Pointer, an Arena and a node:ffi object at every field
or method access. shadow-cljs warns where it cannot infer the type, and an
advanced build renames what has no hint.

The babashka run only sees the code that its binary was built with. A test
for new code fails there until babashka updates the submodule. Guard such a
test with:

```clojure
(when-not (System/getProperty "babashka.version") ...)
```

The guard is on the host, so the test stays off in babashka after the
submodule update too. Once babashka carries the code, drop the guard, and
keep the test inside the native image limits in doc/guide.md under
Performance and limits, such as at most 6 callback arguments. A test of
behavior that already shipped needs no guard and runs on both hosts.

CI runs bb on Linux, macOS and Windows, and the JVM suite on JDK 25
on the same three.

Test conventions:

- Bind libc through the default lookup: (ffi/cfn "abs" [:int] :int). No
  load-library for libc.
- A test that needs a C function with a chosen signature makes one with
  ffi/callback and binds the returned pointer with cfn.
- default-lookup? and struct-lib gate tests that need libc or the fixture.
  A skip prints its cause.
- State the behavior in the deftest name or a testing string, not in a
  comment.

## Lint

```sh
clj-kondo --lint src test test-jvm test-node
```

One pre-existing info about a redundant long coercion in ffi.clj is known.

## Measure

There is no bench directory. Measure with criterium from a script:

```sh
clojure -Sdeps '{:deps {criterium/criterium {:mvn/version "0.4.6"}}}' \
        -J--enable-native-access=ALL-UNNAMED -M bench.clj
```

```clojure
(require '[babashka.ffi :as ffi] '[criterium.core :as c])
(def abs-i (ffi/cfn "abs" [:int] :int))
(c/quick-bench (abs-i -5))
```

Compare a branch against main in the same session. A scalar call on the JVM
is 3 to 6 ns. Anything above 10 ns for a scalar call is a regression.

For the babashka side, build babashka with the submodule pointed at the
branch and time a loop. Numbers for the trampoline path are in
doc/guide.md under Performance and limits.

## Review a PR

1. Run clojure -M:test and clj-kondo on the branch.
2. A change to a type table: check the other tables listed above.
3. A performance claim: run the criterium comparison against main.
4. A new signature shape or limit: check that the guide's Performance and
   limits section still holds.
5. A public API change: check ADR 0004 for argument order and ADR 0007 for
   resolve-once, then regenerate API.md with bb quickdoc.

## Docs

- doc/guide.md is the user guide. State current behavior only.
- API.md is generated by bb quickdoc. Do not edit it by hand.
- doc/ai/adr/ holds one file per decision, numbered. Write a new ADR for a
  change in API shape or a new host mechanism, not for a bug fix.
- Notes on failed attempts and benchmark logs go in doc/ai/, not in the
  guide.
