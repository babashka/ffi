# Examples

Each of these uses `babashka.ffi` and nothing else, so it runs on either host.
`javascriptcore.clj` also needs squint. In babashka it adds the dep with
`add-deps`. On the JVM, put squint on the classpath.

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
- `javascriptcore.clj` embeds JavaScriptCore: squint compiles ClojureScript
  and the engine evaluates it, without Node.js. JavaScript calls a registered
  bb function back. macOS ships the library. On Linux it is in the
  libjavascriptcoregtk-4.1-0 package.
- `python.clj` embeds CPython: it evaluates Python expressions and registers
  a Clojure function as a Python callable. It needs libpython3.
- `helitorus.clj` draws a helix around a torus with raylib
  (`brew install raylib`, or the raylib package of your distribution).
- `doom.clj` is a raycaster with textures and sprites, also through raylib.
- `pacman.clj` is pac-man with the classic ghost personalities, also through
  raylib. An optional argument limits the run to that many seconds.
- `portaudio.clj` plays an arpeggio through a realtime audio callback and
  reports whether the stream underran (`brew install portaudio`, or the
  PortAudio package of your distribution).
- `gtk4.clj` opens a native window that renders from an atom. A button counts
  clicks and a background thread updates a clock (`brew install gtk4`, or the
  GTK 4 package of your distribution).
