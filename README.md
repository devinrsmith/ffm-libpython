# ffm-proto: embedding CPython in Java 25 with FFM

This example starts a CPython interpreter in a Java 25 program, runs a Python
`print` statement, and finalizes the interpreter again. It uses the
[Foreign Function & Memory API](https://openjdk.org/jeps/454) (FFM) through
bindings that [jextract](https://github.com/openjdk/jextract) generates from
`Python.h`. There is no JNI and no C code to compile.

The bindings are generated from the Python 3.12 headers but restricted to
CPython's [Stable ABI](https://docs.python.org/3/c-api/stable.html). So any
libpython 3.12 or later works, and the program picks one when it starts:

```console
$ ./gradlew run -q -Ppython.executable=python3.14
Java: starting embedded Python
Java: embedding /nix/store/lb41b0anx1f98y9y5s9mdv97gjgsq740-python3-3.14.7/lib/libpython3.14.so.1.0
Hello from Python 3.14.7, embedded in Java via FFM!
Java: Python finalized
```

## Requirements

| To…                              | You need                                                                  |
|----------------------------------|---------------------------------------------------------------------------|
| compile (`./gradlew assemble`)   | any JDK 17+ to launch Gradle; Gradle provisions JDK 25 if it isn't installed |
| run or test                      | Python 3.12 or later, built with a shared libpython (`--enable-shared`)   |
| regenerate the bindings          | [jextract](https://jdk.java.net/jextract/) and the Python 3.12 headers    |

On Debian or Ubuntu, the runtime needs the `libpython3.X` package (the
versioned `libpython3.X.so.1.0` is enough). Regenerating needs
`python3.12-dev`.

Only Linux has been tested, with Python 3.12, 3.13, 3.14 and 3.15.

### Choosing the Python at runtime

`LibPython` resolves the libpython to embed when the program starts:

1. `-Dpython.library=/path/to/libpython3.X.so`, if set.
2. Otherwise, it asks the interpreter `-Dpython.executable` (default
   `python3` on the `PATH`) through `sysconfig` where its shared library is.
   It also takes that interpreter's `sys.executable`, and the embedded
   interpreter presents itself as that executable (see
   [Virtual environments](#virtual-environments)).

It then checks that the loaded library's `Py_Version` is 3.12 or later, so a
3.11 library fails with a clear message rather than a crash. `run` forwards
both properties from Gradle properties of the same name:

```sh
./gradlew run -Ppython.executable=/opt/python3.13/bin/python3.13
./gradlew run -Ppython.executable=.venv/bin/python
./gradlew run -Ppython.library=/usr/lib/x86_64-linux-gnu/libpython3.14.so.1.0
JAVA_OPTS=-Dpython.executable=python3.14 build/install/ffm-proto/bin/ffm-proto
```

`test` always embeds a virtual environment in `build/test-venv`, which it
creates from `-Ppython.executable` (default `python3`). So
`./gradlew test -Ppython.executable=python3.15` tests against 3.15.

The resolved libpython must be built with `--enable-shared`. Distro and Nix
Pythons are; pyenv's default builds and some others aren't, and `LibPython`
says so when it runs into one.

<a id="virtual-environments"></a>**Virtual environments.** Point
`python.executable` at the venv's `python`. Its `sysconfig` names the base
installation's libpython, which is loaded. Before initializing,
`EmbeddedPython` calls `Py_SetProgramName` with the venv's `python`. CPython
derives `sys.executable` and `sys.prefix` from the program name, finds the
`pyvenv.cfg` beside it, and so starts *as* the venv, with its site-packages on
`sys.path`. With only `-Dpython.library`, there's no executable to present, so
Python falls back to its default path calculation.

`Py_SetProgramName` has been deprecated since 3.11 in favour of `PyConfig` and
`Py_InitializeFromConfig`. But `PyConfig` isn't in the Stable ABI, and its
layout changes with every minor version (`sizeof` is 432, 448 and 456 bytes in
3.12, 3.13 and 3.14). Bindings generated from the 3.12 header would write to
the wrong offsets on newer Pythons. `Py_SetProgramName` *is* in the Stable ABI
and still works in 3.15. The test asserts that the embedded `sys.prefix` is its
venv, so a future Python that stops honouring the call fails the test rather
than silently ignoring the venv. The forward-compatible replacement, if one is
ever needed, is PEP 741's string-keyed `PyInitConfig` API (3.14+).

### With Nix

`flake.nix` provides a dev shell (Linux) with everything above: JDK 25,
jextract and Python 3.12, which is `python3` in the shell. To try a newer
Python, bring it in too:

```sh
nix shell nixpkgs#python314 -c nix develop -c ./gradlew run -Ppython.executable=python3.14
```

```sh
nix develop
./gradlew build run
./gradlew jextract   # works as-is in this shell
```

The shell also uses
[nix-gradle-wrapper](https://github.com/devinrsmith/nix-gradle-wrapper):

- It copies the Gradle distribution pinned in `gradle-wrapper.properties` into
  the Nix store, so `./gradlew` never downloads Gradle.
- It points `GRADLE_USER_HOME` at an isolated directory
  (`~/.cache/ffm-proto-nix-gradle-home`) that limits Gradle to the shell's
  JDK 25. Gradle won't detect other JDKs on the host or download any.
- It runs the Gradle daemon itself on that JDK.

The shell also sets `CPATH` to glibc's headers so jextract can find them (see
below). It unsets the `PYTHONPATH`, `PYTHONHASHSEED` and `PYTHONNOUSERSITE`
variables that nixpkgs' Python setup hook exports. `PYTHONPATH` in particular
pointed at python312's site-packages, and the embedded interpreter honors it
like `python` does, so it ended up on a 3.15 interpreter's module search path.

## Layout

```
build-logic/                      included build: the `JextractTask` type and plugin
flake.nix                         Nix dev shell (JDK 25, jextract, Python 3.12, vendored Gradle)
gradle/libs.versions.toml         version catalog
jextract/stable.symbols           C symbols to bind from the Stable ABI
jextract/cpython.symbols          ...and from CPython's version-specific API
src/main/jextract/                generated bindings (committed, do not edit)
  .../capi/stable/                  CApi: Stable ABI, plus <dlfcn.h>
  .../capi/cpython/                 CPython: sub-interpreter creation
src/main/java/                    module com.example.ffm.python
  com/example/ffm/python/
    EmbeddedPython.java           the main interpreter: start / exec / close
    SubInterpreter.java           an isolated sub-interpreter with its own GIL
    PythonCode.java               runs source in the current interpreter
    LibPython.java                finds, loads and checks libpython at runtime
    PythonVersion.java            Py_Version / PY_VERSION_HEX, unpacked
    Main.java                     the demo
src/test/java/                    tests (each class in its own JVM)
```

## The bindings

The code under `src/main/jextract` was generated by jextract and is committed.
That means an ordinary build needs neither jextract nor C headers, and the
bindings can be reviewed in diffs. To regenerate them:

```sh
./gradlew jextract
```

The headers come from the interpreter named by the Gradle property
`jextract.python` (default `python3.12`, in `gradle.properties`). It should
stay the *oldest* supported version.

`./gradlew jextract` runs two tasks. Each generates only the symbols listed in
its jextract argument file, into its own package. To bind more of the C API,
add lines to the right file. jextract silently skips symbols it can't find, so
check the generated code afterwards.

**`jextractStableAbi`** reads `jextract/stable.symbols` and generates
`com.example.ffm.python.capi.stable.CApi`. Almost everything comes from here.
It extracts two headers:

- `<Python.h>`, with `Py_LIMITED_API=0x030C0000` defined. CPython then exposes
  only the Stable ABI as of 3.12, the functions it promises to keep in every
  later 3.x. So a symbol jextract can't find is one that newer Pythons
  wouldn't guarantee anyway. For example, `PyRun_SimpleString` isn't in the
  Stable ABI, so `EmbeddedPython.exec` uses `Py_CompileString` and
  `PyEval_EvalCode` instead.
- `<dlfcn.h>`: `dlopen` and `RTLD_*`. See
  [Extension modules](#extension-modules).

To list everything on offer:

```sh
jextract -D Py_LIMITED_API=0x030C0000 -I <include dir> --dump-includes all.symbols '<Python.h>'
```

Function-like macros can't be extracted. Bind the function behind them
instead, for example `Py_DecRef` for `Py_DECREF`.

**`jextractCPython`** reads `jextract/cpython.symbols` and generates
`com.example.ffm.python.capi.cpython.CPython`. This is the exception:
`<Python.h>` without `Py_LIMITED_API`, for the one thing the Stable ABI lacks,
[sub-interpreters with their own GIL](#sub-interpreters). These bindings are
tied to the 3.12 headers, so they're kept to symbols whose declarations and
struct layouts are unchanged in every supported version: `PyInterpreterConfig`
and `PyStatus` are identical from 3.12 through 3.15. A future Python that
changes them would need these bindings regenerated or guarded per version.

No `--library` is given to jextract. The generated code looks symbols up only
through `SymbolLookup.loaderLookup()`, which finds whatever library
`LibPython` has `System.load`ed.

**When libclang can't find the C standard headers.** On mainstream distros
jextract finds `/usr/include` by itself. Nix and similar setups don't expose
it. If you see `'stdlib.h' file not found`, set `CPATH` to the libc include
directory, as the Nix dev shell does. Alternatively, pass extra include
directories, separated by `:`:

```sh
./gradlew jextract -Pjextract.includeDirs=/path/to/libc/include
```

**jextract occasionally crashes.** jextract (from nixpkgs, jextract 23 on
libclang 21) sometimes dies with a SIGSEGV inside libclang and leaves an
`hs_err_pid*.log`. Normal generation crashed in about 1 in 15 to 2 in 50 runs
while I developed this. `--dump-includes` on the whole `Python.h` crashed in
most runs, sometimes several in a row. Rerunning eventually works. I didn't find
the cause: neither a larger thread stack (`JEXTRACT_JAVA_OPTIONS=-Xss16m`) nor
`LIBCLANG_DISABLE_CRASH_RECOVERY=1` helped. The task writes into a
staging directory and syncs into `src/main/jextract` only on success, so a
crash never damages the committed bindings.

## Startup diagnostics

After initializing, before running any Python code, `Main` prints how the
interpreter was configured. `EmbeddedPython.configuration()` collects these
values from CPython's `Py_Get*()` functions. Here is a run in a 3.15 virtual
environment:

```console
$ ./gradlew run -q -Ppython.executable=/home/me/.venv/bin/python
Java: starting embedded Python
Java: embedding /nix/store/…-python3-3.15.0/lib/libpython3.15.so.1.0
Java: as /home/me/.venv/bin/python
Java: Py_Version = 0x030F00F0 = 3.15.0 (final)
Java: Python configuration:
  Py_GetProgramName()     = /home/me/.venv/bin/python
  Py_GetProgramFullPath() = /home/me/.venv/bin/python
  Py_GetPythonHome()      = (not set)
  Py_GetPrefix()          = /home/me/.venv
  Py_GetExecPrefix()      = /home/me/.venv
  Py_GetPath()            = /nix/store/…-python3-3.15.0/lib/python315.zip:/nix/store/…-python3-3.15.0/lib/python3.15:/nix/store/…-python3-3.15.0/lib/python3.15/lib-dynload
  Py_GetVersion()         = 3.15.0 (main, Oct  9 2026, 11:16:41) [GCC 16.2.0]
  Py_GetPlatform()        = linux
  Py_GetCompiler()        = [GCC 16.2.0]
  Py_GetBuildInfo()       = main, Oct  9 2026, 11:16:41
Hello from Python 3.15.0, embedded in Java via FFM!
sys.prefix = /home/me/.venv
Java: running 3 sub-interpreters on 3 threads
[sub-interpreter 0] own sys module at 0x7fffc46c2750, native thread 37162
[sub-interpreter 2] own sys module at 0x7fffc44be750, native thread 37164
[sub-interpreter 1] own sys module at 0x7fffc42ba750, native thread 37163
Java: Python finalized
```

`EmbeddedPython.version()` returns the `Py_Version` runtime constant as a
`PythonVersion` record: `major`, `minor`, `micro`, `releaseLevel` (`ALPHA`,
`BETA`, `CANDIDATE` or `FINAL`, with Python's `sys.version_info` names) and
`releaseSerial`. Unlike the `PY_VERSION_HEX` macro, which describes the headers
code was compiled against (3.12 here), `Py_Version` describes the library
actually loaded. `PythonVersion` also packs back to `hex()`, orders like the
packed value (`3.14.0a1 < 3.14.0 < 3.14.1`), and prints as Python does
(`3.12.5rc2`). `LibPython` uses it for the 3.12 minimum check.

The `Py_Get*()` values come in two groups:

- **Path configuration** (`wchar_t *`): `Py_GetProgramName`,
  `Py_GetProgramFullPath`, `Py_GetPythonHome`, `Py_GetPrefix`,
  `Py_GetExecPrefix` and `Py_GetPath`. These have been deprecated since 3.13
  in favour of `sys` and `PyConfig_Get()`. They're no longer declared in the
  3.15 headers, but they're part of the Stable ABI, so 3.15 still exports them
  and the bindings generated from 3.12 still work. `Py_GetPath()` is the module
  search path *before* `site` runs, so it doesn't include site-packages.
- **Build** (`const char *`): `Py_GetVersion`, `Py_GetPlatform`,
  `Py_GetCompiler` and `Py_GetBuildInfo`. `Py_GetCopyright` returns a
  multi-line legal notice rather than configuration, so it isn't bound.

**In a venv, `Py_GetPrefix()` and `Py_GetExecPrefix()` change meaning between
versions.** Up to 3.13 they report the venv's *base* installation, even though
`sys.prefix` is the venv. From 3.14 they report the venv. `Py_GetProgramName()`
and `Py_GetProgramFullPath()` name the venv's `python` on every version, so use
those (or `sys.prefix`) to tell whether a venv is in effect.

## Sub-interpreters

`EmbeddedPython.newSubInterpreter()` creates an isolated sub-interpreter
with **its own GIL**, using `Py_NewInterpreterFromConfig` with CPython's
isolated defaults. Each one has its own modules, `sys` and `__main__`. Since
they don't share a GIL, sub-interpreters on different Java threads run Python
code in parallel. The tests check this by timing: two CPU-bound loops on two
threads take about as long as one.

```java
try (EmbeddedPython python = EmbeddedPython.start()) {
    // On any platform thread:
    try (SubInterpreter sub = python.newSubInterpreter()) {
        sub.exec("import math; print(math.pi)");
    }
}
```

The rules, all enforced:

- **Confined to its creating thread.** CPython ties a sub-interpreter's thread
  state to that OS thread. For the same reason, virtual threads are rejected,
  since they can move between OS threads.
- **Close sub-interpreters before the main interpreter.**
  `EmbeddedPython.close()` refuses while any are open, because CPython 3.12
  can't finalize around live sub-interpreters.
- **Some extension modules can't be imported.** Isolated interpreters accept
  only extension modules that support multi-phase initialization. That covers
  most of the stdlib (`math`, `json`, `zlib`, `_sqlite3`, `_ssl`, …). Every
  version refuses `readline` and `_curses`, and 3.12 also refuses `_ctypes`,
  `_decimal` and `_datetime`. A refused import is an ordinary `ImportError`,
  which `exec` throws as a `PythonException`.
- Isolated interpreters also can't fork, exec, or start daemon threads.

Creation briefly attaches the calling thread to the main interpreter with
`PyGILState_Ensure`, as `Py_NewInterpreterFromConfig` requires, and detaches
again before returning. `exec` attaches only the sub-interpreter's own thread
state, so running code never contends for the main GIL.

## Design notes

- **Native access is explicit.** FFM downcalls are restricted methods. The app
  runs as a named module with `--enable-native-access=com.example.ffm.python`,
  so only this module may call native code and the JVM prints no warnings.
  Tests run on the classpath, so they use `ALL-UNNAMED`. `javac` runs with
  `-Xlint:all -Werror`, minus the `restricted` lint, which would flag every
  FFM call.
- **The JVM keeps its signal handlers.** The interpreter is started with
  `Py_InitializeEx(0)` rather than `Py_Initialize()`, so Python doesn't install
  its own handlers for signals like SIGINT.
- <a id="extension-modules"></a>**Extension modules.** `System.load` opens
  libpython with `RTLD_LOCAL`. C extension modules such as `math` resolve
  symbols like `PyExc_StopIteration` from the global namespace, so without a
  fix even `import math` fails with "undefined symbol". `LibPython` re-opens
  the already-loaded libpython with `RTLD_NOW | RTLD_NOLOAD | RTLD_GLOBAL`.
- **Threads and the GIL.** `EmbeddedPython` is confined to the thread that
  started it. That thread holds the main GIL only during each call
  (`PyEval_RestoreThread` … `PyEval_SaveThread`) and releases it in between, so
  other threads can create [sub-interpreters](#sub-interpreters) and Python's
  own threads can run.
- **Start the interpreter once per process.** CPython can't be reliably
  re-initialized in the same process, so the build runs each test class in its
  own JVM (`forkEvery = 1`).
- **Python's output doesn't go through `System.out`.** Python writes straight
  to the native stdout and stderr. When stdout isn't a terminal, Python buffers
  that output until finalization, so it can interleave with Java's output in
  unexpected ways.
- **Memory.** Java strings are copied into native memory as NUL-terminated
  UTF-8 in a confined `Arena`, which frees them when the call returns. Python
  objects returned as new references (the compiled code and its result) are
  released with `Py_DecRef`.
- **`SystemExit`.** As with `PyRun_SimpleString`, an uncaught `SystemExit` in
  `exec` reaches `PyErr_Print`, which exits the process, JVM included.

## Build

- Gradle 9.8.1 wrapper with a pinned distribution checksum
- Kotlin DSL, version catalog, and the configuration cache plus build cache
  enabled in `gradle.properties`
- a Java toolchain (25), provisioned through the foojay resolver if it's missing
- `JvmTestSuite` with JUnit Jupiter
- build logic in an included build (`build-logic`). `JextractTask` is lazily
  configured and safe for the configuration cache. Nothing queries Python until
  a task that needs it runs.
- commands given by name (`jextract`, `python3.12`, the test venv's interpreter)
  are resolved against the invoking shell's `PATH`, not the Gradle daemon's.
  The daemon's `PATH` is fixed when it starts, which breaks after switching
  shells, for example between `nix shell`s.
