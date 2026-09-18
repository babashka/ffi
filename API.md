# Table of contents
-  [`babashka.ffi`](#babashka.ffi)  - Call functions in native shared libraries.
    -  [`address`](#babashka.ffi/address) - Returns the native address of pointer p as a Clojure long.
    -  [`alignof`](#babashka.ffi/alignof) - Returns the alignment, in bytes, of type keyword t or of a struct layout.
    -  [`alloc`](#babashka.ffi/alloc) - Allocates zeroed native memory in arena and returns its pointer.
    -  [`auto-arena`](#babashka.ffi/auto-arena) - Returns an arena that the garbage collector manages.
    -  [`byte-buffer`](#babashka.ffi/byte-buffer) - Returns a java.nio.ByteBuffer view of n bytes of native memory at pointer p.
    -  [`callback`](#babashka.ffi/callback) - Creates a C function pointer that invokes f.
    -  [`cfn`](#babashka.ffi/cfn) - Creates a Clojure function that calls the C function sym.
    -  [`clone`](#babashka.ffi/clone) - Allocates a copy of pointer src in arena with the same size and returns the new pointer.
    -  [`confined-arena`](#babashka.ffi/confined-arena) - Returns an arena for one thread.
    -  [`copy`](#babashka.ffi/copy) - Copies bytes from pointer src to pointer dst.
    -  [`defcfn`](#babashka.ffi/defcfn) - Defines name as a C function binding created by cfn: (defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int) (defcfn sqlite3-open "Opens the database at path, storing the handle in out-param pp." "sqlite3_open" [:string :pointer] :int) An optional docstring and attribute map can precede the C symbol, argument types, and return type.
    -  [`find-symbol`](#babashka.ffi/find-symbol) - Finds sym and returns a pointer to it.
    -  [`global-arena`](#babashka.ffi/global-arena) - Returns the global arena.
    -  [`load-library`](#babashka.ffi/load-library) - Loads a shared library and adds it to the symbol search.
    -  [`load-system-library`](#babashka.ffi/load-system-library) - Loads a shared library by its short name.
    -  [`null`](#babashka.ffi/null) - The NULL pointer.
    -  [`null?`](#babashka.ffi/null?) - Returns true for a NULL pointer.
    -  [`place`](#babashka.ffi/place) - Returns a place for read and write in layout t.
    -  [`pointer?`](#babashka.ffi/pointer?) - Returns true when x is a pointer: a MemorySegment of native memory.
    -  [`ptr->string`](#babashka.ffi/ptr->string) - Returns the NUL-terminated UTF-8 string at p.
    -  [`read`](#babashka.ffi/read) - Reads a value of type t from p.
    -  [`read-array`](#babashka.ffi/read-array) - Copies n elements of type t from pointer p, at byte offset (default 0), into a new Java array.
    -  [`reinterpret`](#babashka.ffi/reinterpret) - Returns a view of segment seg with byte size size.
    -  [`segment`](#babashka.ffi/segment) - Returns a pointer to addr.
    -  [`shared-arena`](#babashka.ffi/shared-arena) - Returns an arena for multiple threads.
    -  [`size`](#babashka.ffi/size) - Returns the size of pointer p in bytes.
    -  [`sizeof`](#babashka.ffi/sizeof) - Returns the size of a type keyword or struct layout, in bytes.
    -  [`slice`](#babashka.ffi/slice) - Returns a slice of seg at byte offset.
    -  [`string->ptr`](#babashka.ffi/string->ptr) - Copies s into arena as a NUL-terminated UTF-8 string and returns its pointer.
    -  [`with-open`](#babashka.ffi/with-open) - Evaluates body with each name bound to its value.
    -  [`write`](#babashka.ffi/write) - Writes v as type t to p.
    -  [`write-array`](#babashka.ffi/write-array) - Copies Java array arr into memory at pointer p, at byte offset (default 0), as elements of type t.

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
Use reinterpret to specify their size before access.

:bool represents a one-byte C boolean and returns true or false.

A layout describes memory: [:struct [[name type] ...]] for a struct and
[:array type n] for a fixed array. read returns a struct as a map and an
array as a vector. write accepts a map for a struct and a sequence for an
array. A field of a struct can be either, so `char name[32]` is
[:name [:array :char 32]].

[:union [[name type] ...]] describes a C union. read returns a union as a
pointer to its bytes. Read the active member from that pointer using its
type. write takes a [member value] pair. Unions cannot be passed by value.

Use place to select a layout member by name or by a path of names and array
indices. Pass the result to read or write instead of a type. A place stores
the member's offset and type.

read-array and write-array copy elements of one scalar type between
native memory and a Java array of that width, as a memcpy.

Use a layout in a function signature to pass or return a struct by value.
Represent struct values as maps:

    (ffi/defcfn c-div "div" [:int :int] [:struct [[:quot :int] [:rem :int]]])
    (c-div 7 2)   ;=> {:quot 3 :rem 1}

On the JVM, struct calls use the FFM linker and need only the JDK. Native
images use libffi for struct calls. See doc/guide.md.

Native images compile a fixed set of fast call shapes: up to six
arguments, at most three mixed floating-point arguments or four of the
same floating-point type, up to 10 integer or pointer arguments, and a
:float return with up to four arguments. A fixed signature outside this
set requires libffi. Binding fails if libffi is unavailable.

Native images use libffi for every variadic call. Without libffi, a
variadic call throws. In a native image, callbacks support up to four
arguments with at most two :double arguments, or up to six
integer and pointer arguments. Callbacks do not support :float. The
callback return type must be :void, an integer type, :pointer, or :double.
Argument order does not affect these limits. See doc/guide.md for details
and workarounds.

Add :& to argtypes to declare a variadic C function. The types before :& are
the fixed parameters. Types after :& declare the tail once. With no types
after :&, each call infers the tail types from its values.
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
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L464-L467">Source</a></sub></p>

## <a name="babashka.ffi/alignof">`alignof`</a>
``` clojure
(alignof t)
```
Function.

Returns the alignment, in bytes, of type keyword t or of a struct layout.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1372-L1375">Source</a></sub></p>

## <a name="babashka.ffi/alloc">`alloc`</a>
``` clojure
(alloc arena n)
(alloc arena n alignment)
```
Function.

Allocates zeroed native memory in arena and returns its pointer.
n is an integer byte count, a type keyword, or a struct layout.

Use a confined arena for access from one thread or a shared arena for
access from multiple threads. Closing the arena releases its memory.

A type or layout uses natural alignment. An integer byte count uses
alignment 16. Specify an alignment to override this value.

For memory allocated by C, bind the allocator with cfn and release the
result with the matching C deallocator.

CAUTION: Do not close the arena while C uses its memory.
C can access released memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1428-L1451">Source</a></sub></p>

## <a name="babashka.ffi/auto-arena">`auto-arena`</a>
``` clojure
(auto-arena)
```
Function.

Returns an arena that the garbage collector manages.
Keep the arena reachable while C uses its pointers. You cannot close it.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1389-L1393">Source</a></sub></p>

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
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1745-L1756">Source</a></sub></p>

## <a name="babashka.ffi/callback">`callback`</a>
``` clojure
(callback arena f argtypes rettype)
```
Function.

Creates a C function pointer that invokes f. arena owns the pointer, which
is valid until the arena releases it.
argtypes and rettype use the cfn type keywords. f receives :pointer arguments
as zero-size pointers and :bool arguments as booleans. Numeric arguments
are passed as numbers. For a :pointer return, f must return a pointer or
nil for NULL.

Choose the arena for the thread that calls back:

    (ffi/callback (ffi/shared-arena) f [:pointer] :void)

A shared arena allows C to invoke the callback from any thread, including a
thread that your code did not create. Use it for asynchronous callbacks, such
as event-loop notifications. A confined arena accepts a call from its own
thread only. Use it for synchronous callbacks, such as a comparison
function. A global arena never releases the pointer.

An automatic arena releases the pointer once the pointer itself becomes
unreachable. The garbage collector cannot see the copy that C holds. Use an
automatic arena only when your reference outlives every call that C can make.

CAUTION: Unregister the callback before its arena releases the pointer.
Catch exceptions inside f. An uncaught exception can stop the process.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L2495-L2600">Source</a></sub></p>

## <a name="babashka.ffi/cfn">`cfn`</a>
``` clojure
(cfn sym argtypes rettype)
(cfn lib sym argtypes rettype)
```
Function.

Creates a Clojure function that calls the C function sym. sym is a C symbol
name or a function pointer. argtypes is a vector of argument types. rettype
is the return type. Use type keywords for scalars and layouts for structs
passed by value. Struct values are maps of their fields. Struct calls
require libffi in a native image and only the JDK on the JVM.

Use a function pointer for a function that has no exported name. The pointer
can come from a loader, C function, struct field, find-symbol, or callback.

A library value limits the search to one library and its dependencies.
Without a library value, cfn searches all loaded libraries. Then it searches
the default system lookup. The first call resolves the symbol. You can
create the binding before you load its library.

A :& in argtypes declares a variadic C function. The types before :& are
the fixed parameters. Types after :& declare the variadic argument types.
With no types after :&, each call infers them from its values.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1023-L1098">Source</a></sub></p>

## <a name="babashka.ffi/clone">`clone`</a>
``` clojure
(clone arena src)
```
Function.

Allocates a copy of pointer src in arena with the same size and returns
the new pointer. Use reinterpret to specify a size for pointers from C.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1659-L1666">Source</a></sub></p>

## <a name="babashka.ffi/confined-arena">`confined-arena`</a>
``` clojure
(confined-arena)
```
Function.

Returns an arena for one thread.
Create this arena in with-open to release its memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1377-L1381">Source</a></sub></p>

## <a name="babashka.ffi/copy">`copy`</a>
``` clojure
(copy src dst)
(copy src dst n)
```
Function.

Copies bytes from pointer src to pointer dst. Without n, copies the byte
size of src. dst must be at least that large. With n, copies n bytes.
Returns nil.

Use reinterpret to specify a size for pointers from C. To copy into the
middle of dst, slice it first:

    (ffi/copy src (ffi/slice dst 16) n)

Supports overlapping regions, as with memmove.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1639-L1657">Source</a></sub></p>

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

An optional docstring and attribute map can precede the C symbol, argument
types, and return type. Preserves metadata on name, including ^:private.

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
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1230-L1338">Source</a></sub></p>

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
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L751-L759">Source</a></sub></p>

## <a name="babashka.ffi/global-arena">`global-arena`</a>
``` clojure
(global-arena)
```
Function.

Returns the global arena. Its memory exists until the process stops.
You cannot close this arena.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1395-L1399">Source</a></sub></p>

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
In that form, cfn searches this library and its dependencies.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L639-L675">Source</a></sub></p>

## <a name="babashka.ffi/load-system-library">`load-system-library`</a>
``` clojure
(load-system-library name)
```
Function.

Loads a shared library by its short name. For example, "z" selects
libz.dylib, libz.so, or z.dll. On Linux, the search also includes versioned
names such as libz.so.1. Returns the same library map as load-library.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L677-L716">Source</a></sub></p>

## <a name="babashka.ffi/null">`null`</a>




The NULL pointer.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1764-L1766">Source</a></sub></p>

## <a name="babashka.ffi/null?">`null?`</a>
``` clojure
(null? p)
```
Function.

Returns true for a NULL pointer. Returns false for all other pointers.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1768-L1771">Source</a></sub></p>

## <a name="babashka.ffi/place">`place`</a>
``` clojure
(place t)
(place t path)
```
Function.

Returns a place for read and write in layout t. path is a member name
or a vector of member names and array indices. Without a path, returns
a place for the whole layout.

    (def parent (place bone :parent))
    (read p parent)                          ;=> 7
    (write p parent 3)
    (read p (place outer [:msgs 1 :data :result]))
    (read p (place point))

Uses the member's type for reads and writes: a struct as a map, an array
as a vector, a union as a pointer on read and a pair on write. A path to
a union member accepts the member's value directly on write.

Throws for an invalid path. Create a place once and reuse it.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1723-L1743">Source</a></sub></p>

## <a name="babashka.ffi/pointer?">`pointer?`</a>
``` clojure
(pointer? x)
```
Function.

Returns true when x is a pointer: a MemorySegment of native memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L475-L478">Source</a></sub></p>

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

limit is a maximum byte count. If p has a nonzero size, the read is also
bounded by that size. Throws if no NUL byte occurs within these bounds.

CAUTION: Without a limit, ptr->string can read past a buffer that has no
NUL byte. This can stop the process.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L492-L529">Source</a></sub></p>

## <a name="babashka.ffi/read">`read`</a>
``` clojure
(read p t)
(read p t offset)
```
Function.

Reads a value of type t from p. The default byte offset is zero.

t is a type keyword, a layout, or a place returned by place. A place
specifies the layout member's type and offset.

Checks the access against the size of p. Rejects a zero-size pointer.
reinterpret specifies a valid size.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1499-L1533">Source</a></sub></p>

## <a name="babashka.ffi/read-array">`read-array`</a>
``` clojure
(read-array p t n)
(read-array p t n offset)
```
Function.

Copies n elements of type t from pointer p, at byte offset (default 0),
into a new Java array. Returns the array.

Copies raw bytes without converting elements. For example,
:int, :uint and :int32 return an int[] with the same bits, so a
:uint above Integer/MAX_VALUE reads as a negative int. :long and the other
eight-byte types fill a long[], and :pointer fills a long[] of addresses.
:byte, :char, :int8, :uint8 and :bool fill a byte[]. For pointers, use
read with [:array :pointer n].

For an array of structs, or for elements decoded the way read decodes
them, use read with an [:array t n] layout.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1599-L1619">Source</a></sub></p>

## <a name="babashka.ffi/reinterpret">`reinterpret`</a>
``` clojure
(reinterpret seg size)
(reinterpret seg size arena)
(reinterpret seg size arena cleanup)
```
Function.

Returns a view of segment seg with byte size size.

Without an arena, the view retains seg's lifetime.

With an arena, the view is valid only while that arena is open. A read after
the arena closes throws. The arena calls the optional cleanup function with
the view when it closes. Use this function for a C library deallocator.

CAUTION: Give the actual size. The runtime cannot know if this size is
correct. A larger size permits out-of-bounds reads.

CAUTION: If the arena is closed, do not pass the view to C. C can access the
released memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L426-L446">Source</a></sub></p>

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
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L416-L424">Source</a></sub></p>

## <a name="babashka.ffi/shared-arena">`shared-arena`</a>
``` clojure
(shared-arena)
```
Function.

Returns an arena for multiple threads.
Create this arena in with-open to release its memory.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1383-L1387">Source</a></sub></p>

## <a name="babashka.ffi/size">`size`</a>
``` clojure
(size p)
```
Function.

Returns the size of pointer p in bytes. A pointer that C returned has
size 0.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L469-L473">Source</a></sub></p>

## <a name="babashka.ffi/sizeof">`sizeof`</a>
``` clojure
(sizeof t)
```
Function.

Returns the size of a type keyword or struct layout, in bytes. The size
of a struct includes padding.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1366-L1370">Source</a></sub></p>

## <a name="babashka.ffi/slice">`slice`</a>
``` clojure
(slice seg offset)
(slice seg offset len)
```
Function.

Returns a slice of seg at byte offset. By default, the slice ends with seg.
len is an integer byte count, a type keyword, or a layout. To select one
struct from an array:

    (slice arr (* i (sizeof point)) point)

CAUTION: Keep offset before len. A transposed call throws only if the result
does not fit in seg.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L450-L462">Source</a></sub></p>

## <a name="babashka.ffi/string->ptr">`string->ptr`</a>
``` clojure
(string->ptr arena s)
```
Function.

Copies s into arena as a NUL-terminated UTF-8 string and returns its
pointer. The arena controls the lifetime of the string.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1758-L1762">Source</a></sub></p>

## <a name="babashka.ffi/with-open">`with-open`</a>
``` clojure
(with-open bindings & body)
```
Macro.

Evaluates body with each name bound to its value. Calls .close on each
value in reverse order when body returns or throws.

CAUTION: On Node.js the arena closes when body returns. Do not return a
promise that still uses it.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1401-L1415">Source</a></sub></p>

## <a name="babashka.ffi/write">`write`</a>
``` clojure
(write p t v)
(write p t v offset)
```
Function.

Writes v as type t to p. The default byte offset is zero. Returns nil.

t is a type keyword, a layout, or a place returned by place. When a place
selects a union member, pass the member's value directly.

Checks the access against the size of p. Rejects a zero-size pointer.
reinterpret specifies a valid size.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1535-L1564">Source</a></sub></p>

## <a name="babashka.ffi/write-array">`write-array`</a>
``` clojure
(write-array p t arr)
(write-array p t arr offset)
```
Function.

Copies Java array arr into memory at pointer p, at byte offset (default
0), as elements of type t. Returns nil.

Copies raw bytes without converting elements. arr must be a Java array
of the matching type: an int[] for :int, a long[] for :long or :pointer, a
byte[] for :char.
<p><sub><a href="https://github.com/babashka/ffi/blob/main/src/babashka/ffi.clj#L1621-L1637">Source</a></sub></p>
