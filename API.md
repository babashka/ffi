# Table of contents
-  [`babashka.ffi`](#babashka.ffi)  - Call functions in native shared libraries.
    -  [`address`](#babashka.ffi/address) - Returns the native address of pointer p as a Clojure long.
    -  [`alignof`](#babashka.ffi/alignof) - Returns the alignment, in bytes, of type keyword t or of a struct layout.
    -  [`alloc`](#babashka.ffi/alloc) - Allocates zeroed native memory in arena and returns its pointer.
    -  [`auto-arena`](#babashka.ffi/auto-arena) - Returns an arena that the garbage collector manages.
    -  [`byte-buffer`](#babashka.ffi/byte-buffer) - Returns a java.nio.ByteBuffer view of n bytes of native memory at pointer p.
    -  [`callback`](#babashka.ffi/callback) - Creates a C function pointer that invokes f.
    -  [`cfn`](#babashka.ffi/cfn) - Creates a Clojure function that calls the C function sym.
    -  [`confined-arena`](#babashka.ffi/confined-arena) - Returns an arena for one thread.
    -  [`defcfn`](#babashka.ffi/defcfn) - Defines name as a C function binding created by cfn: (defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int) (defcfn sqlite3-open "Opens the database at path, storing the handle in out-param pp." "sqlite3_open" [:string :pointer] :int) An optional docstring and attribute map can precede the C symbol.
    -  [`find-symbol`](#babashka.ffi/find-symbol) - Finds sym and returns a pointer to it.
    -  [`global-arena`](#babashka.ffi/global-arena) - Returns the global arena.
    -  [`load-library`](#babashka.ffi/load-library) - Loads a shared library and adds it to the symbol search.
    -  [`load-system-library`](#babashka.ffi/load-system-library) - Loads a shared library by its short name.
    -  [`null`](#babashka.ffi/null) - The NULL pointer.
    -  [`null?`](#babashka.ffi/null?) - Returns true for a NULL pointer.
    -  [`pointer?`](#babashka.ffi/pointer?) - Returns true when x is a pointer: a MemorySegment of native memory.
    -  [`ptr->string`](#babashka.ffi/ptr->string) - Returns the NUL-terminated UTF-8 string at p.
    -  [`read`](#babashka.ffi/read) - Reads a value of type t from p.
    -  [`read-bytes`](#babashka.ffi/read-bytes) - Copies n bytes from pointer p at byte offset (default 0) into a new byte array.
    -  [`reinterpret`](#babashka.ffi/reinterpret) - Returns a view of segment seg with byte size size.
    -  [`segment`](#babashka.ffi/segment) - Returns a pointer to addr.
    -  [`shared-arena`](#babashka.ffi/shared-arena) - Returns an arena for multiple threads.
    -  [`size`](#babashka.ffi/size) - Returns the size of pointer p in bytes.
    -  [`sizeof`](#babashka.ffi/sizeof) - Returns the size of a type keyword or struct layout, in bytes.
    -  [`slice`](#babashka.ffi/slice) - Returns a slice of seg at byte offset.
    -  [`string->ptr`](#babashka.ffi/string->ptr) - Copies s into arena as a NUL-terminated UTF-8 string and returns its pointer.
    -  [`write`](#babashka.ffi/write) - Writes v as type t to p.
    -  [`write-bytes`](#babashka.ffi/write-bytes) - Copies byte array arr into memory at pointer p at byte offset (default 0).

-----
# <a name="babashka.ffi">babashka.ffi</a>


Call functions in native shared libraries.

Load a library, bind C functions with explicit argument and return types,
and manage native memory:

    (require '[babashka.ffi :as ffi])
    (ffi/load-system-library "sqlite3")
    (def sqlite3-open (ffi/cfn "sqlite3_open" [:string :pointer] :int))
    (with-open [arena (ffi/confined-arena)]
      (let [pp (ffi/alloc arena :pointer)]
        (sqlite3-open "x.db" pp)
        (ffi/read pp :pointer)))

Every allocation belongs to an arena. The arena controls the lifetime of
the memory.

Use these type keywords:

    :void
    :int :uint :long :ulong :int8 :uint8 :int16 :uint16 :int32
    :uint32 :int64 :uint64 :size_t :ssize_t :char :byte
    :bool :pointer :string :double :float

A pointer is a native java.lang.foreign.MemorySegment with a size. read and
write check each access against this size. Pointers from C have size zero.
reinterpret specifies their size before access. :bool
represents a one-byte C boolean and returns true or false. Thus, a C
predicate does not return the truthy number 0.

A layout describes memory: [:struct [[name type] ...]] for a struct and
[:array type n] for a fixed array. read returns a struct as a map and an
array as a vector. write accepts a map for a struct and a sequence for an
array. A field of a struct can be either, so `char name[32]` is
[:name [:array :char 32]].

A function that takes a struct as an argument, or returns one, without a
pointer in between, gets a layout on that position in the signature. A
struct value is a map of its fields:

    (ffi/defcfn c-div "div" [:int :int] [:struct [[:quot :int] [:rem :int]]])
    (c-div 7 2)   ;=> {:quot 3 :rem 1}

On the JVM, struct calls use the FFM linker and need only the JDK. Native
images use libffi for struct calls. See doc/guide.md.

Native images compile a fixed set of fast call shapes: up to six
arguments, at most three mixed floating-point arguments or four of the
same floating-point type, up to 10 integer or pointer arguments, and a
:float return with up to four arguments. A fixed signature outside this
set calls through libffi, at about 1 microsecond instead of about 100
nanoseconds. Without libffi, such a signature throws.

Native images use libffi for every variadic call. If libffi is not
available, variadic calls use the FFM fallback. This fallback supports at
most five total arguments, three fixed arguments, none of them :float,
and two :double arguments. Its return type must be :void, an integer
type, or a pointer type. Callbacks
support up to four arguments and two :double arguments. Callbacks do not
support :float. The callback return type must be :void, an integer type, or
:double. Argument order does not affect these limits. See doc/guide.md for
details and workarounds.

Add a trailing :& to declare a variadic C function. The types before :& are
the fixed parameters. Each call infers the tail types from the values.
Integers and pointers use 64-bit integers. C promotion converts floats to
doubles. Strings use C strings:

    (ffi/defcfn c-open "open" [:string :int :&] :int)
    (c-open path O_RDONLY)         ; empty tail
    (c-open path flags 0644)       ; one-int tail, same binding




## <a name="babashka.ffi/address">`address`</a>
``` clojure
(address p)
```
Function.

Returns the native address of pointer p as a Clojure long.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L279-L282">Source</a></sub></p>

## <a name="babashka.ffi/alignof">`alignof`</a>
``` clojure
(alignof t)
```
Function.

Returns the alignment, in bytes, of type keyword t or of a struct layout.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1026-L1029">Source</a></sub></p>

## <a name="babashka.ffi/alloc">`alloc`</a>
``` clojure
(alloc arena n)
(alloc arena n alignment)
```
Function.

Allocates zeroed native memory in arena and returns its pointer.
n is an integer byte count, a type keyword, or a struct layout.

Use a confined arena inside one function. Use a shared arena for memory that
outlives the call and is released elsewhere. When the arena closes, it
releases its memory.

A type or layout uses natural alignment. An integer byte count uses
alignment 16. Specify an alignment to override this value.

There is no unscoped form. If C allocates the memory, bind its allocator with
cfn. Release the result with the matching C deallocator.

CAUTION: Do not close the arena while C uses its memory.
C can access released memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1066-L1090">Source</a></sub></p>

## <a name="babashka.ffi/auto-arena">`auto-arena`</a>
``` clojure
(auto-arena)
```
Function.

Returns an arena that the garbage collector manages.
Keep the arena reachable while C uses its pointers. You cannot close it.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1043-L1047">Source</a></sub></p>

## <a name="babashka.ffi/byte-buffer">`byte-buffer`</a>
``` clojure
(byte-buffer p n)
```
Function.

Returns a java.nio.ByteBuffer view of n bytes of native memory at pointer p.
The buffer and native memory share the same bytes.

CAUTION: Do not use the buffer after you release the native memory. An
invalid memory access can stop the process.

The byte order is big-endian, as it is for each new ByteBuffer. If you need a
different byte order, set it with .order.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1202-L1213">Source</a></sub></p>

## <a name="babashka.ffi/callback">`callback`</a>
``` clojure
(callback arena f argtypes rettype)
```
Function.

Creates a C function pointer that invokes f. arena owns the pointer, which
is valid until the arena releases it. There is no separate release function.
argtypes and rettype use the cfn type keywords. f receives :pointer arguments
as zero-size pointers. It receives
:bool arguments as booleans and other arguments as longs or doubles.

Choose the arena for the thread that calls back:

    (ffi/callback (ffi/shared-arena) f [:pointer] :void)

A shared arena allows C to invoke the callback from any thread, including a
thread that your code did not create. Use it for asynchronous callbacks, such
as event-loop notifications. A confined arena accepts a call from its own
thread only. If C calls back during a call that you make, use this arena, such
as for a comparison function. A global arena never releases the pointer.

An automatic arena releases the pointer once the pointer itself becomes
unreachable. The garbage collector cannot see the copy that C holds. Use an
automatic arena only when your reference outlives every call that C can make.

CAUTION: C can call the pointer until its arena releases it, and not one
instruction longer. Unregister the callback first.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1810-L1894">Source</a></sub></p>

## <a name="babashka.ffi/cfn">`cfn`</a>
``` clojure
(cfn sym argtypes rettype)
(cfn lib sym argtypes rettype)
```
Function.

Creates a Clojure function that calls the C function sym. sym is a C symbol
name or a function pointer. argtypes is a vector of type keywords. rettype
is a type keyword. A struct that the function takes as an argument, or
returns, without a pointer in between, is a layout on that position, and
its value is a map of its fields. On the JVM, struct calls use the FFM linker
and need only the JDK. Native images use libffi for struct calls.

Use a function pointer for a function that has no exported name. The pointer
can come from a loader, C function, struct field, find-symbol, or callback.

A library value limits the search to one library and its dependencies.
Without a library value, cfn searches all loaded libraries. Then it searches
the default system lookup. The first call resolves the symbol and creates
the call handle. You can create the binding before you load its library.

A trailing :& declares a variadic C function. The types before :& are the
fixed parameters. Each call infers the tail types from its values.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L725-L788">Source</a></sub></p>

## <a name="babashka.ffi/confined-arena">`confined-arena`</a>
``` clojure
(confined-arena)
```
Function.

Returns an arena for one thread.
Create this arena in with-open to release its memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1031-L1035">Source</a></sub></p>

## <a name="babashka.ffi/defcfn">`defcfn`</a>
``` clojure
(defcfn name docstring? attr-map? sym argtypes rettype)
(defcfn name docstring? attr-map? sym argtypes rettype native-fn & fn-tail)
```
Macro.

Defines name as a C function binding created by cfn:

    (defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)

    (defcfn sqlite3-open
      "Opens the database at path, storing the handle in out-param pp."
      "sqlite3_open" [:string :pointer] :int)

An optional docstring and attribute map can precede the C symbol. The final
three arguments are the C symbol, argument types, and return type. defcfn
preserves all metadata on name. This metadata includes ^:private.

The :library key in the attribute map selects a library for cfn:

    (def sqlite (delay (ffi/load-library (extract-bundled-library!))))
    (defcfn sqlite3-open {:library sqlite} "sqlite3_open"
      [:string :pointer] :int)

The value can be a library map or a function that returns one. It can also
be an IDeref object that holds a library map.

Without :library, a binding searches all loaded libraries. Then it searches
the default system lookup. A system library with the same name can supply
the symbol.

The wrapper form binds the raw C function to a local name and defines name
as the wrapper:

    (defcfn open-db
      "sqlite3_open_v2" [:string :pointer :int :string] :int
      open-native
      [filename flags]
      (with-open [arena (ffi/confined-arena)]
        (let [pdb (ffi/alloc arena :pointer)
              code (open-native filename pdb flags nil)]
          (if (zero? code)
            (ffi/read pdb :pointer)
            (throw (ex-info "open failed" {:code code}))))))

The symbol after the return type names the raw binding. Only the wrapper
body can use this name. The forms after the raw name are a normal fn tail.
The wrapper can have multiple arities. Its argument lists can differ from
the C function. The raw name does not enter the namespace. The wrapper
form needs a literal argtypes vector. Only the plain form accepts an
argtypes expression.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L881-L990">Source</a></sub></p>

## <a name="babashka.ffi/find-symbol">`find-symbol`</a>
``` clojure
(find-symbol sym)
(find-symbol lib sym)
```
Function.

Finds sym and returns a pointer to it. Returns nil for an unknown symbol.

A library value limits the search to one library and its dependencies.
Without a library value, find-symbol searches all loaded libraries. Then it
searches the default system lookup.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L566-L574">Source</a></sub></p>

## <a name="babashka.ffi/global-arena">`global-arena`</a>
``` clojure
(global-arena)
```
Function.

Returns the global arena. Its memory exists until the process stops.
You cannot close this arena.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1049-L1053">Source</a></sub></p>

## <a name="babashka.ffi/load-library">`load-library`</a>
``` clojure
(load-library lib)
```
Function.

Loads a shared library and adds it to the symbol search.

Use load-system-library for file names that follow platform conventions.

lib can be a path, a vector of candidates, or a map of operating systems to
candidates. The function tries vector entries in order. An operating-system
map uses the keys :mac, :linux, and :windows:

    (ffi/load-library
      {:mac ["/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib"
             "/usr/local/opt/openssl@3/lib/libcrypto.3.dylib"]
       :linux "libcrypto.so.3"})

:darwin is an alias for :mac. For a bare name, the function also searches
common installation directories. Returns a library map whose :path value
identifies the loaded candidate. The map can be the first argument to cfn.
In that form, cfn searches only this library.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L454-L490">Source</a></sub></p>

## <a name="babashka.ffi/load-system-library">`load-system-library`</a>
``` clojure
(load-system-library name)
```
Function.

Loads a shared library by its short name. For example, "z" selects
libz.dylib, libz.so, or z.dll. On Linux, the search also includes versioned
names such as libz.so.1. Returns the same library map as load-library.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L492-L531">Source</a></sub></p>

## <a name="babashka.ffi/null">`null`</a>




The NULL pointer.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1221-L1223">Source</a></sub></p>

## <a name="babashka.ffi/null?">`null?`</a>
``` clojure
(null? p)
```
Function.

Returns true for a NULL pointer. Returns false for all other pointers.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1225-L1228">Source</a></sub></p>

## <a name="babashka.ffi/pointer?">`pointer?`</a>
``` clojure
(pointer? x)
```
Function.

Returns true when x is a pointer: a MemorySegment of native memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L290-L293">Source</a></sub></p>

## <a name="babashka.ffi/ptr->string">`ptr->string`</a>
``` clojure
(ptr->string p)
(ptr->string p limit)
```
Function.

Returns the NUL-terminated UTF-8 string at p. Returns nil for a NULL
pointer.

A pointer returned by C has no size, so the read runs to the first NUL
byte. This is what a :string return type does.

Give a limit in bytes. If no NUL appears within the limit, `ptr->string`
throws an error. A limit only narrows: a pointer with a known size keeps it.

CAUTION: Without a limit, ptr->string can read past a buffer that has no
NUL byte. This can stop the process.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L307-L344">Source</a></sub></p>

## <a name="babashka.ffi/read">`read`</a>
``` clojure
(read p t)
(read p t offset)
```
Function.

Reads a value of type t from p. The default byte offset is zero.

Checks the access against the size of p. Rejects a zero-size pointer.
reinterpret specifies a valid size.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1128-L1155">Source</a></sub></p>

## <a name="babashka.ffi/read-bytes">`read-bytes`</a>
``` clojure
(read-bytes p n)
(read-bytes p n offset)
```
Function.

Copies n bytes from pointer p at byte offset (default 0) into a new byte
array.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1181-L1190">Source</a></sub></p>

## <a name="babashka.ffi/reinterpret">`reinterpret`</a>
``` clojure
(reinterpret seg size)
(reinterpret seg size arena)
(reinterpret seg size arena cleanup)
```
Function.

Returns a view of segment seg with byte size size.

Without an arena the view has an unbounded lifetime. That is correct for
memory that C owns and that outlives your code.

With an arena, the view is valid only while that arena is open. A read after
the arena closes throws. The arena calls the optional cleanup function with
the view when it closes. Use this function for a C library deallocator.

CAUTION: Give the actual size. The runtime cannot know if this size is
correct. A larger size permits out-of-bounds reads.

CAUTION: If the arena is closed, do not pass the view to C. C can access the
released memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L240-L261">Source</a></sub></p>

## <a name="babashka.ffi/segment">`segment`</a>
``` clojure
(segment addr)
(segment addr size)
```
Function.

Returns a pointer to addr. The default size is zero.
A specified nonzero size enables bounds checks.

CAUTION: Keep addr before size. A transposed call can stop the process at
the first read.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L230-L238">Source</a></sub></p>

## <a name="babashka.ffi/shared-arena">`shared-arena`</a>
``` clojure
(shared-arena)
```
Function.

Returns an arena for multiple threads.
Create this arena in with-open to release its memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1037-L1041">Source</a></sub></p>

## <a name="babashka.ffi/size">`size`</a>
``` clojure
(size p)
```
Function.

Returns the size of pointer p in bytes. A pointer that C returned has
size 0.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L284-L288">Source</a></sub></p>

## <a name="babashka.ffi/sizeof">`sizeof`</a>
``` clojure
(sizeof t)
```
Function.

Returns the size of a type keyword or struct layout, in bytes. The size
of a struct includes padding.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1020-L1024">Source</a></sub></p>

## <a name="babashka.ffi/slice">`slice`</a>
``` clojure
(slice seg offset)
(slice seg offset len)
```
Function.

Returns a slice of seg at byte offset. By default, the slice ends with seg.
len is an integer byte count, a type keyword, or a struct layout, so walking
an array of structs takes the layout itself:

    (slice arr (* i (sizeof point)) point)

CAUTION: Keep offset before len. A transposed call throws only if the result
does not fit in seg.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L265-L277">Source</a></sub></p>

## <a name="babashka.ffi/string->ptr">`string->ptr`</a>
``` clojure
(string->ptr arena s)
```
Function.

Copies s into arena as a NUL-terminated UTF-8 string and returns its
pointer. The arena controls the lifetime of the string.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1215-L1219">Source</a></sub></p>

## <a name="babashka.ffi/write">`write`</a>
``` clojure
(write p t v)
(write p t v offset)
```
Function.

Writes v as type t to p. The default byte offset is zero. Returns nil.

Checks the access against the size of p. Rejects a zero-size pointer.
reinterpret specifies a valid size.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1157-L1179">Source</a></sub></p>

## <a name="babashka.ffi/write-bytes">`write-bytes`</a>
``` clojure
(write-bytes p arr)
(write-bytes p arr offset)
```
Function.

Copies byte array arr into memory at pointer p at byte offset (default
0).
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1192-L1200">Source</a></sub></p>
