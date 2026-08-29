# babashka.ffi

`babashka.ffi` calls functions in native shared libraries.

The API is experimental.

This library is built-in to babashka but it also runs on the JVM.

On the JVM, you need to:

- Start the JVM with `--enable-native-access=ALL-UNNAMED`.
- Set the `Enable-Native-Access` manifest attribute in an uberjar.

Without either setting, you'll get a warning from the JDK. A future release will refuse the
calls without these settings.

## Contents

- [Quickstart](#quickstart)
- [Load a library](#load-a-library)
- [Bind a function](#bind-a-function)
  - [Bind an address](#bind-an-address)
  - [Wrap the binding in one form](#wrap-the-binding-in-one-form)
  - [Types](#types)
  - [Pass a struct by value](#pass-a-struct-by-value)
- [Call a variadic function](#call-a-variadic-function)
- [Use native memory](#use-native-memory)
  - [Memory that C allocated](#memory-that-c-allocated)
  - [Arenas](#arenas)
  - [Read and write a struct](#read-and-write-a-struct)
  - [Fixed arrays](#fixed-arrays)
  - [Unions](#unions)
  - [Out parameters](#out-parameters)
- [Create a callback](#create-a-callback)
- [Performance and limits](#performance-and-limits)
  - [On the JVM](#on-the-jvm)
  - [In a babashka native binary](#in-a-babashka-native-binary)
  - [Callbacks](#callbacks)
- [Examples](#examples)

## Quickstart

Load a library and bind a function:

```clojure
(require '[babashka.ffi :as ffi :refer [defcfn]])

(def zlib (ffi/load-system-library "z"))
(def zlib-version (ffi/cfn zlib "zlibVersion" [] :string))

(zlib-version)
;;=> "1.3.1"
```

`load-system-library` adds the platform file name. For example, `"z"`
becomes `libz.dylib`, `libz.so`, or `z.dll`, depending on the operating system.

## Load a library

Use `load-system-library` for a short library name:

```clojure
(ffi/load-system-library "z")
```

On Linux, this function also searches for versioned names such as
`libz.so.1`, in the `LD_LIBRARY_PATH` directories and the directories
listed below.

Use `load-library` for an exact file name or path:

```clojure
(ffi/load-library "/exact/path/libfoo.so")
```

`load-library` does not change the candidate names.

Pass a vector to try multiple candidates in order:

```clojure
(ffi/load-library ["libfoo.so.3" "libfoo.so"])
```

Pass a map to select candidates for each operating system:

```clojure
(ffi/load-library
 {:mac ["/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib"
        "/usr/local/opt/openssl@3/lib/libcrypto.3.dylib"]
  :linux "libcrypto.so.3"
  :windows "libcrypto-3-x64.dll"})
```

The supported keys are `:mac`, `:linux`, and `:windows`. You can use
`:darwin` instead of `:mac`.

Both functions first ask the operating system to load the library. If this
fails for a bare name, they search these directories:

macOS:

- `/opt/homebrew/lib`
- `/usr/local/lib`
- `/opt/local/lib`
- `/usr/lib`

Linux:

- the directories in `LD_LIBRARY_PATH`
- `/usr/local/lib`
- `/usr/lib64`
- `/usr/lib`
- `/usr/lib/x86_64-linux-gnu` for x86_64 systems
- `/usr/lib/aarch64-linux-gnu` for AArch64 systems
- `/lib64`
- `/lib`
- `/lib/x86_64-linux-gnu` or `/lib/aarch64-linux-gnu`, for systems where
  `/lib` is not merged into `/usr/lib`

On Windows, add the directory that contains the DLL to `PATH` before you start
babashka. Alternatively, pass the full DLL path to `load-library`. Windows uses
its [DLL search path](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-search-order)
to find other DLL files that the loaded DLL needs.

On FreeBSD, babashka runs as a Linux binary through the
[Linuxulator](https://docs.freebsd.org/en/books/handbook/linuxemu/). The
Linuxulator translates `/usr/lib64` and `/lib64` to
`/compat/linux/usr/lib64` and `/compat/linux/lib64`. As a result, the load
functions find libraries in those translated directories.

Both functions return a library map. The `:path` value contains the loaded
candidate:

```clojure
(def zlib (ffi/load-system-library "z"))
(:path zlib)
;;=> "libz.dylib"
```

Pass this map to `cfn` to limit the search to that library and its
dependencies:

```clojure
(def zlib-version (ffi/cfn zlib "zlibVersion" [] :string))
```

Without a library map, `cfn` searches all loaded libraries and the default
system lookup. `find-symbol` follows the same rules.

A shared library exports functions and global variables by name. An exported
name is a symbol.

Use `find-symbol` to access a symbol without a function binding:

```clojure
(ffi/find-symbol "zlibVersion")
;;=> a pointer
```

Pass the result to a C function that accepts a function or data pointer. You
can also pass it to `cfn` to bind it.

If `find-symbol` cannot find the symbol, it returns `nil`.

Pass a library map to limit the search to that library and its dependencies:

```clojure
(ffi/find-symbol zlib "zlibVersion")
```

Without a library map, `find-symbol` searches all loaded libraries and then
the default system lookup.

If the selected library and a dependency export the same symbol, the search
returns the symbol from the selected library.

The search can also find symbols from the dependencies. For example,
`(ffi/find-symbol zlib "strlen")` returns the address of the C library's
`strlen`.

## Bind a function

Use `cfn` to create a Clojure function:

```clojure
(def z-error (ffi/cfn zlib "zError" [:int] :string))
(z-error -3)
;;=> "data error"
```

The arguments to `cfn` are the C symbol, argument types, and return type.
The symbol lookup occurs on the first call.

### Bind an address

`cfn` also accepts a function address instead of a name:

```clojure
(def c-abs (ffi/cfn (ffi/find-symbol "abs") [:int] :int))
(c-abs -42)
;;=> 42
```

Use this form for a function without an exported name.

Function addresses can come from `find-symbol`, C return values, struct fields,
or `callback`.

`cfn` rejects address zero. A function lookup usually returns zero when it
cannot find the requested function.

CAUTION: Make sure that the address points to a function with the declared
signature. An incorrect address or signature can stop the process.

Use `defcfn` to define and bind a function:

```clojure
(defcfn zlib-version "zlibVersion" [] :string)
(zlib-version)
;;=> "1.3.1"
```

You can add a docstring and an attribute map before the C symbol:

```clojure
(defcfn zlib-version
  "Returns the zlib version."
  {:added "1.0"}
  "zlibVersion" [] :string)
```

The `:library` key in the attribute map selects the library for the binding:

```clojure
(def sqlite (delay (ffi/load-library (extract-bundled-library!))))

(defcfn sqlite3-open {:library sqlite}
  "sqlite3_open" [:string :pointer] :int)
```

If you ship a library with your application, use `:library`.

Without this key, the binding searches all loaded libraries and then the
system.

A system library with the same name can then supply the symbol. As a result,
the application can call a version that you did not select.

`:library` accepts one of these values:

- A library map
- A function that returns a library map
- A `delay`, `atom`, or var that holds a library map.

At the first call, the binding gets the library and resolves the symbol. The
binding keeps the function address.

A function or `delay` can refer to a library that loads later. Changes to the
library value after the first call do not change the binding.

### Define a wrapper with `defcfn`

Use the wrapper form of `defcfn` to define a raw binding and a wrapper
together:

```clojure
(defcfn open-db
  "sqlite3_open_v2" [:string :pointer :int :string] :int
  open-native
  [filename flags]
  (with-open [arena (ffi/confined-arena)]
    (let [pdb  (ffi/alloc arena :pointer)
          code (open-native filename pdb flags nil)]
      (if (zero? code)
        (ffi/read pdb :pointer)
        (throw (ex-info "open failed" {:code code}))))))
```

The symbol after the return type names the raw binding. Only the wrapper body
can use this name. The raw name does not become a var in the namespace.

The forms after the raw name are a normal `fn` tail. The wrapper can have
multiple arities. Its argument lists can differ from the C function.

The wrapper form requires a static argument type vector. The plain form also
accepts a dynamic expression.

### Types

The keywords, denoting C types can be used in function signatures.

| Type | Meaning |
|---|---|
| `:void` | No return value. Do not use it as an argument type. |
| `:int`, `:int32` | Signed 32-bit integer. |
| `:uint`, `:uint32` | Unsigned 32-bit integer. |
| `:long`, `:int64` | Signed 64-bit integer. |
| `:ulong`, `:uint64` | Unsigned 64-bit integer bits in a Clojure long. |
| `:int16` | Signed 16-bit integer. |
| `:uint16` | Unsigned 16-bit integer. |
| `:int8`, `:byte`, `:char` | Signed 8-bit integer. |
| `:uint8` | Unsigned 8-bit integer. |
| `:size_t` | Unsigned 64-bit size. |
| `:ssize_t` | Signed 64-bit size. |
| `:float` | 32-bit floating-point number. |
| `:double` | 64-bit floating-point number. |
| `:bool` | One-byte C boolean. |
| `:pointer` | A pointer, see [Use native memory](#use-native-memory). |
| `:string` | Pointer to a NUL-terminated UTF-8 string. |

`:long` and `:ulong` are always 64-bit types. A C `long` is 32 bits on
Windows. Use the type that matches the C declaration.

A `:bool` argument uses Clojure truthiness. A `:bool` return value is
`true` or `false`:

```clojure
(defcfn window-should-close? "WindowShouldClose" [] :bool)
(when-not (window-should-close?) ...)
```

A `:uint8` return value is a number. In Clojure, both `0` and `1` are
truthy.

A `:string` argument points to temporary memory. The pointer is valid only
until the C function returns.

If the C function stores the pointer, allocate the string with `string->ptr`
in an arena (see [Arenas](#arenas)). Keep the arena open while C uses the
pointer.

A `:string` return value reads the pointer as UTF-8. A NULL return value
becomes `nil`.

### Pass a struct by value

A C function can take a struct as an argument, or return one, without a
pointer in between. On that position in the signature, write a layout
instead of a type keyword. A struct layout has the form `[:struct fields]`.
Each field is a `[name type]` pair in C declaration order. The order sets
the memory offsets. The name sets only the map key. A type is a type
keyword or another layout. A struct value is a map of
its fields, in any order:

```clojure
(defcfn c-div "div" [:int :int] [:struct [[:quot :int] [:rem :int]]])
(c-div 7 2)
;;=> {:quot 3 :rem 1}
```

Layouts nest, and so do their values:

```clojure
(def point [:struct [[:x :int] [:y :int]]])
(def rect [:struct [[:lo point] [:hi point]]])
(defcfn rect-grow "rect_grow" [rect :int] rect)
(rect-grow {:lo {:x 1 :y 1} :hi {:x 5 :y 5}} 2)
;;=> {:lo {:x -1 :y -1} :hi {:x 7 :y 7}}
```

A struct value must contain each layout field and no other field. A missing or
unknown field causes an error.

`sizeof` and `alignof` accept a layout. `sizeof` accounts for the alignment of
each struct field. In this example, the struct has seven padding bytes between
`:c` and `:d`:

```clojure
(ffi/sizeof [:struct [[:c :char] [:d :double]]])
;;=> 16
(ffi/alignof [:struct [[:c :char] [:d :double]]])
;;=> 8
```

To map a struct to a value of your own, wrap the binding:

```clojure
(defn body-position [id]
  (let [{:keys [x y z]} (c-body-position id)]
    (vec3 x y z)))
```

On the JVM, a struct call uses the FFM linker. The linker builds a downcall
handle from the struct layout. This requires only the JDK.

A native image cannot build this handle at run time. It can call only
signatures that were registered when the image was built. A struct descriptor
carries the whole layout, so a fixed set of registrations cannot cover every
struct. Native images use [libffi](https://github.com/libffi/libffi) for these
calls. Libffi places the arguments from a description that it builds at run
time. When `babashka.ffi` binds the function, it compares its struct layout
with the layout that libffi computes. A difference is an error.

A struct call takes approximately 1 microsecond in a native image. A call
with only primitive types takes approximately 150 nanoseconds.

Every babashka binary includes `libffi`, except the musl static binary and a
build made with `BABASHKA_LIBFFI=none`. `bb describe` shows the version under
`:libffi/version`. In a binary without libffi, a struct binding causes an
error.

This implementation does not support structs in variadic signatures.

A `:string` struct field follows the same [pointer-lifetime rules](#types) as a
`:string` argument.

## Call a variadic function

Put `:&` after the fixed argument types:

```clojure
(defcfn c-open "open" [:string :int :&] :int)

(c-open path O_RDONLY)
(c-open path flags 0644)
```

The values after the fixed arguments determine the variadic types:

| Clojure value | Variadic type |
|---|---|
| Integer, pointer, boolean, or `nil` | 64-bit integer |
| Floating-point number or ratio | `double` |
| String | NUL-terminated C string |

The fixed arguments and variadic values must match the C function contract.
For example, a `printf` format must match its values.

```clojure
(defcfn c-printf "printf" [:string :&] :int)
(c-printf "%s: %.0f\n" "count" 42.0)
```

## Use native memory

Pointers refer to native memory. The API rejects these values before a call to
C:

- a heap segment without a C address
- memory from a closed arena
- memory from a confined arena created by another thread

On the JVM, a pointer is a `java.lang.foreign.MemorySegment`. Babashka does not
expose this class to scripts because it significantly increases the binary
size. Use `size`, `address`, `slice`, `reinterpret`, and `pointer?` to work with
a `MemorySegment` instead.

CAUTION: Do not pass a confined segment from another thread. C can bypass the
thread-access restriction.

`alloc` returns a segment with a size. Access outside a nonzero segment throws
an `IndexOutOfBoundsException`.

C does not report the size of a returned pointer. Thus, the pointer has size
zero. Memory access functions reject these pointers.

Before you access the memory, specify its size with `reinterpret`:

```clojure
;; C returned p without a size. The struct has 16 bytes.
(ffi/read (ffi/reinterpret p 16) :int 8)
```

`alloc 0` and an end-of-block slice also have size zero. `ptr->string` rejects
size zero and reads other pointers within their size. Declare a C string return
type as `:string`.

Use `size` to get the segment size. Use `address` to convert a pointer to a
long. Use `segment` to convert a raw address to a pointer. Use `slice` to
select part of a segment. The `+` function does not support pointers.

```clojure
(ffi/size p)             ;;=> 16
(ffi/address p)          ;;=> 4438706736
(ffi/segment 4438706736) ;;=> a pointer of size 0
(ffi/segment addr 16)    ;;=> a pointer of size 16
(ffi/slice p 8)          ;;=> the rest of p from byte 8
(ffi/reinterpret p 64)   ;;=> p with size 64
```

A C pointer argument accepts a pointer or `nil`. A `nil` value is NULL.
Pointer arguments reject numbers. `ffi/null` is the NULL pointer:

```clojure
(ffi/null? ffi/null)
;;=> true
```

Every allocation belongs to an [arena](#arenas). The arena controls the
lifetime of the memory. `alloc` always takes an arena:

```clojure
(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena 16)]
    (ffi/write p :int 42)
    (ffi/read p :int)))
;;=> 42
```

`alloc` also takes a type keyword or a struct layout instead of an integer
byte count. It uses the natural alignment of the type or layout:

```clojure
(ffi/alloc arena :pointer)                          ; 8 bytes
(ffi/alloc arena [:struct [[:x :int] [:y :int]]])   ; 8 bytes, aligned for the struct
```

### Memory that C allocated

A C function can return memory that the caller has to release. Give that
pointer to an arena, with the library's own deallocator as the cleanup
function. The arena calls the deallocator when it closes:

```clojure
(defcfn duckdb-free "duckdb_free" [:pointer] :void)

(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/reinterpret (c-value-varchar res 0 0) 64 arena duckdb-free)]
    (ffi/ptr->string p)))
```

Use the deallocator that the library documents, such as `duckdb_free` or
`sqlite3_free`. A library allocates from its own heap. On Windows a library
built against another C runtime has a different heap, and the wrong
deallocator corrupts it.

For memory that the C allocator returned, and a library that documents no
deallocator of its own, bind `free`:

```clojure
(defcfn c-free "free" [:pointer] :void)
```

CAUTION: After the deallocator runs, do not use the pointer. This can corrupt
memory or stop the process.

### Arenas

An arena owns its allocated memory. When the arena closes, it releases this
memory.

Create an arena in `with-open` to close it automatically:

```clojure
(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena :int)
        q (ffi/alloc arena 256)]
    (ffi/write p :int 42)
    (ffi/read p :int)))
;;=> 42
```

The arena releases `p` and `q` when the body ends. It also releases them if the
body throws. After release, memory access throws an `IllegalStateException`.
C functions reject pointers from a closed arena.

CAUTION: While C uses the arena memory, do not close the arena, since C can continue to use the memory after the arena closes.

`alloc` chooses the correct alignment for a type or layout. For a byte count, it
uses 16-byte alignment. Specify another alignment only when the C API requires
it:

```clojure
(ffi/alloc arena 4096 64)   ; 4096 bytes on a 64-byte boundary
```

Use `confined-arena` for memory that should only be accessible from one thread.
Use `shared-arena` for memory that should be accessible from multiple threads.
Both arena types work with `with-open`.

CAUTION: While another thread is in a C call that uses the shared arena
memory, do not close the arena. The call could continue to use the released memory and the arena does not protect you from that

The garbage collector releases an `auto-arena` after it becomes unreachable.
While C uses its pointers, keep the arena reachable.

A `global-arena` exists until the process stops. You cannot close an automatic
or global arena.

`read` and `write` accept an optional byte offset, and it is always the last
argument:

```clojure
(ffi/write p :int 42)        ; at offset 0
(ffi/write p :double 1.5 8)  ; at offset 8

(ffi/read p :int)
(ffi/read p :double 8)
```

`read` supports each listed type except `:void`. `write` also excludes
`:string`. Write a string address as `:pointer`.

Use `read-array` and `write-array` to copy elements of one scalar type between
native memory and a Java primitive array. The copy uses `memcpy` and does not
decode each element. Both functions accept an optional byte offset:

```clojure
(ffi/write-array p :byte (byte-array [1 2 3 4]))
(ffi/read-array p :byte 4)
;;=> byte array [1 2 3 4]
(ffi/write-array p :int (int-array [1 2 3 4]))
(ffi/read-array p :int 4)
;;=> int array [1 2 3 4]
(ffi/read-array p :double 512 4096)   ; 512 doubles from byte offset 4096
```

The type gives the element width and nothing else. `:int`, `:uint` and
`:int32` fill an `int[]` with the bits as they are, so a `:uint` above
`Integer/MAX_VALUE` reads as a negative int. The eight-byte types fill a
`long[]`, and `:pointer` fills a `long[]` of addresses. For elements decoded
the way `read` decodes them, or for an array of structs, use `read` with an
`[:array t n]` layout.

Use `byte-buffer` to create a zero-copy `java.nio.ByteBuffer` view of native
memory:

```clojure
(ffi/byte-buffer p 4096)
```

The buffer and native memory share the same bytes.

CAUTION: After you release the native memory, do not use the buffer. An
invalid memory access can stop the process.

Use `sizeof` to get the size of a type:

```clojure
(ffi/sizeof :pointer)
;;=> 8
```

Use `string->ptr` to allocate a C string in an arena:

```clojure
(with-open [arena (ffi/confined-arena)]
  (ffi/ptr->string (ffi/string->ptr arena "hello")))
;;=> "hello"
```

`ptr->string` reads a string at the specified address. It returns `nil` for
the NULL address. A pointer returned by C has no size, so `ptr->string` reads
until the first NUL byte. This is the behavior of a `:string` return type:

```clojure
(ffi/ptr->string (duckdb-value-varchar res col row))
```

If you know the size of the buffer, give a limit in bytes. If the buffer has no
NUL byte within the limit, the function throws an error instead of reading past
the buffer:

```clojure
(ffi/ptr->string p 4096)
```

A limit only narrows the read. A pointer with a known size keeps that size, even
when the limit is larger.

If memory contains a string pointer, use `read` with `:string`:

```clojure
(ffi/read pointer-slot :string)
```

This operation first reads the pointer from `pointer-slot`. Then it reads
the string at that pointer.

CAUTION: Use only valid addresses and offsets. An invalid memory access can
stop the process.

### Read and write a struct

`read` and `write` support struct layouts in place of type keywords. `read`
returns the struct as a map. `write` accepts a map:

```clojure
(def point [:struct [[:x :int] [:y :int]]])

(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena point)]
    (ffi/write p point {:x 3 :y 4})
    (ffi/read p point)))
;;=> {:x 3, :y 4}
```

A C function can fill a struct through an out parameter. The next example shows
the required calls:

```clojure
(defcfn fill-point "fill_point" [:pointer :int :int] :void)

(with-open [arena (ffi/confined-arena)]
  (let [out (ffi/alloc arena point)]
    (fill-point out 7 11)
    (ffi/read out point)))
;;=> {:x 7, :y 11}
```

The byte offset selects one element of an array of structs:

```clojure
(ffi/read arr point (* i (ffi/sizeof point)))
```

Layouts nest, and a nested struct is a nested map.

A `:string` field differs from the others. Every other field holds its value
inside the struct, so writing it creates nothing. A `:string` field holds a
pointer to bytes that live elsewhere and must outlive the write, so those
bytes need an owner. Allocate them and write the pointer:

```clojure
(def named [:struct [[:id :int] [:name :string]]])

(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena named)]
    (ffi/write p named {:id 7 :name (ffi/string->ptr arena "seven")})
    (ffi/read p named)))
;;=> {:id 7, :name "seven"}
```

`read` has no such restriction. It copies the bytes out, which raises no
question of ownership.

### Fixed arrays

A C struct often holds a fixed array: `char name[32]`, `int32_t v[4]`,
`double m[2][2]`. The layout for one is `[:array elem n]`. `elem` is a type
keyword or a layout, so an array can hold structs or other arrays:

```clojure
(def bone [:struct [[:name [:array :char 32]] [:parent :int]]])   ; raylib BoneInfo
(def quad [:struct [[:v [:array :int 4]]]])
(def mat2 [:struct [[:m [:array [:array :double 2] 2]]]])
```

`read` returns an array as a vector. `write` accepts a vector, a list, or a
Java array with exactly `n` elements. A value with another length is an error,
just as a struct value with a missing field is an error:

```clojure
(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena quad)]
    (ffi/write p quad {:v [1 2 3 4]})
    (ffi/read p quad)))
;;=> {:v [1 2 3 4]}
```

A `char` array reads as a vector of bytes. C uses `char` for both strings and
raw bytes. To read the string in a fixed-width field, read that field with a
limit:

```clojure
(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena bone)]
    (fill-bone p)
    (ffi/ptr->string (ffi/slice p 0 32) 32)))
;;=> "spine"
```

The limit stops the read at the end of the field when the name fills it
without a NUL byte.

C never passes an array by value. A parameter declared as an array is a
pointer to its first element, so declare `:pointer` for it. A struct that
holds an array is passed by value in the normal way, and `cfn` rejects a bare
array layout in a signature.

### Unions

A C union is `[:union [[name type] ...]]`. It is as large as its largest
member and aligned to its strictest one, so a struct that holds a union
gets the offsets the compiler gives it:

```clojure
(def curl-msg
  [:struct [[:msg :int]
            [:easy :pointer]
            [:data [:union [[:whatever :pointer] [:result :int]]]]]])
```

A union carries no tag of its own. In C the program knows which member is
live: from a sibling field, as in `CURLMsg`, or from what it stored, as in
`epoll_data_t`. So `read` returns a union as a pointer to its bytes, sized
to the union, and you read the member you know applies:

```clojure
(def CURLMSG_DONE 1)   ; curl/multi.h

(let [{:keys [msg data]} (ffi/read p curl-msg)]
  (when (= msg CURLMSG_DONE)
    (ffi/read data :int)))       ; the :result member
```

`write` takes a map with exactly one key, the member to write:

```clojure
(ffi/write p curl-msg {:msg 1 :easy nil :data {:result 0}})
```

A union is not passed by value in a signature, alone or inside a struct;
declare `:pointer` and read it from memory. Every union in the libraries
surveyed for this design arrives through a pointer.

### Out parameters

Allocate memory for a C out parameter. Then pass its address to the C
function:

```clojure
(defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)

(def database
  (with-open [arena (ffi/confined-arena)]
    (let [database-pointer (ffi/alloc arena :pointer)]
      (sqlite3-open "example.db" database-pointer)
      (ffi/read database-pointer :pointer))))
```

The returned database pointer is managed by SQLite. Define the related close
function and call it when you finish with the database:

```clojure
(defcfn sqlite3-close "sqlite3_close" [:pointer] :int)
(sqlite3-close database)
;;=> 0
```

## Create a callback

Use `callback` to pass a Clojure function to C:

```clojure
(with-open [arena (ffi/confined-arena)]
  (let [comparator
        (ffi/callback
         arena
         (fn [left-pointer right-pointer]
           (compare (ffi/read (ffi/reinterpret left-pointer 4) :int)
                    (ffi/read (ffi/reinterpret right-pointer 4) :int)))
         [:pointer :pointer]
         :int)]
    (qsort values 5 4 comparator)))
```

`callback` returns a function pointer. The arena owns the pointer, exactly
as it owns the memory that `alloc` returns. The pointer is valid until the
arena releases it.

`callback` has no separate release function. The owning arena controls the
pointer lifetime.

Choose the arena for the thread that calls back:

- A confined arena accepts a call from its own thread only. If C calls back
  during a call that you make, use this arena. The comparison function above
  uses this pattern. This arena is the cheapest one.
- A shared arena allows C to invoke the callback from any thread, including a
  thread that your code did not create. Use it for asynchronous callbacks, such
  as event-loop notifications.
- A global arena never releases the pointer. Use one for a callback that
  lives as long as the process, such as a signal handler.
- An automatic arena releases the pointer when the pointer itself becomes
  unreachable. The garbage collector cannot see the copy that C holds. Use
  this arena only when your reference outlives every call that C makes.

A `:pointer` callback argument comes from C and has size zero.

Before you read the memory, specify its size with `reinterpret`.

If C keeps the callback, keep its pointer. Before the arena releases the
callback, unregister it.

A `:bool` callback argument becomes `true` or `false`.

CAUTION: Do not let a callback throw an exception. Catch exceptions inside
the callback, or the process can stop.

## Performance and limits

The host determines how `babashka.ffi` calls C and how much each call costs.
Binding metadata shows which backend it uses:

```clojure
(meta (ffi/cfn "abs" [:int] :int))
;;=> #:babashka.ffi{:backend :trampoline}
```

### On the JVM

On the JVM, the FFM linker supports every signature, structs by value
included. It creates a downcall handle for each signature, and the JIT
compiles the handle. This path has no fixed signature limits, and it needs
nothing on the system beyond the JDK.

A primitive call costs about 40 nanoseconds once the loop around it is
compiled. A struct call uses a confined arena for its arguments and return
value. This lets threads share a binding and lets a call re-enter it.

### In a babashka native binary

An image cannot compile a downcall handle at run time, so babashka carries a
fixed set of call signatures compiled ahead of time. A signature in that set
calls through a trampoline in about 30 nanoseconds, which is faster than the
JVM manages. The set covers:

- A function with up to 6 arguments.
- Up to 3 `:float` or `:double` arguments in any combination, or 4 of the
  same floating-point type.
- A function with only integer or pointer arguments, up to 10 arguments.
- A function that returns `:float`, up to 4 arguments.

Argument order does not change this set.

The set is a chosen balance between image size and call speed: every shape
in it adds compiled code to the babashka binary. If a shape you need is
missing, or a call you make often falls back to libffi, open an issue. The
set can grow.

Everything else calls through libffi: a fixed signature outside the set,
every variadic call, and every struct call. A libffi call takes about 1
microsecond. Every babashka binary includes libffi, except the musl static
binary and a build made with `BABASHKA_LIBFFI=none`. `bb describe` shows the
version under `:libffi/version`.

In a build without libffi, a fixed signature outside the set throws.
Variadic calls use the FFM fallback with these limits:

- At most five total arguments.
- At most three fixed arguments, none of them `:float`.
- At most two `:double` arguments.
- A `:void`, integer, or pointer return type.

These figures include only the call itself. In babashka, the interpreter
usually costs more. A `loop` with `recur` adds roughly 30 nanoseconds per
iteration before C runs.

### Callbacks

Callbacks do not use libffi on either host and keep these limits:

- A callback can have up to 4 arguments.
- A callback can have up to 2 `:double` arguments.
- A callback cannot use `:float`.
- A return type can be `:void`, an integer type, or `:double`.

These limits are the same balance between image size and coverage. If a C
API needs a callback shape outside this set, open an issue.

## Examples

The [examples](../examples) directory contains complete programs for SQLite,
CPython, libffi, and raylib, with a note on running each on either host.

These libraries use `babashka.ffi`:

- [babashka.sqlite](https://github.com/babashka/babashka.sqlite), including
  callbacks and aggregates
- [babashka.duckdb](https://github.com/babashka/babashka.duckdb)
