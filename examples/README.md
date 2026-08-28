# Examples

Each of these uses `babashka.ffi` and nothing else, so it runs on either host.

In babashka the namespace is built in:

    bb sqlite.clj

On the JVM, add this library as a dependency and start with native access
enabled:

    clojure -Sdeps '{:deps {io.github.babashka/ffi {:git/url "https://github.com/babashka/ffi" :git/sha "..."}}}' \
            -J--enable-native-access=ALL-UNNAMED -M sqlite.clj

The guide in [doc/guide.md](../doc/guide.md) explains the API these use.

- `sqlite.clj` queries an in-memory sqlite database. It needs the sqlite3
  shared library, which macOS and most Linux systems already have.
- `structs.clj` returns a struct by value and compares its speed with a
  primitive call.
- `libffi.clj` binds libffi through `babashka.ffi` itself, then calls a
  function that returns a struct by value. The bounded FFI bootstraps an
  unbounded one.
- `python.clj` embeds CPython: it evaluates Python expressions and registers
  a Clojure function as a Python callable. It needs libpython3.
- `helitorus.clj` draws a helix around a torus with raylib
  (`brew install raylib`, or the raylib package of your distribution).
- `doom.clj` is a raycaster with textures and sprites, also through raylib.
