# ffm-proto: embedding CPython in Java 25 with FFM

This project embeds a CPython interpreter in a Java 25 program, using the
[Foreign Function & Memory API](https://openjdk.org/jeps/454) (FFM) through
bindings that [jextract](https://github.com/openjdk/jextract) generates from
`Python.h`. There is no JNI glue and no C code to compile.

The bindings are generated from the Python 3.12 headers and restricted to
CPython's [Stable ABI](https://docs.python.org/3/c-api/stable.html). So any
libpython 3.12 or later works, chosen when the program starts. Where newer
versions offer better functions, the code switches at runtime. It's tested on
Linux with Python 3.12, 3.13, 3.14 and 3.15.

The demo (`Main`):

1. finds and loads a libpython (from a venv, if you point it at one) and
   initializes the interpreter;
2. prints the interpreter's version and configuration;
3. if [jpy](https://github.com/jpy-consortium/jpy) is installed, loads its
   native library from Java first and imports it in Python;
4. runs a Python `print`;
5. runs three sub-interpreters on three threads, one sharing the main GIL and
   two with their own, then finalizes.

The full output is in [What the demo prints](#what-the-demo-prints).

**Contents:** [Quick start](#quick-start) ·
[Requirements](#requirements) · [Using it](#using-it) ·
[What the demo prints](#what-the-demo-prints) ·
[Loading an extension module from Java first](#loading-an-extension-module-from-java-first) ·
[Sub-interpreters](#sub-interpreters) · [The bindings](#the-bindings) ·
[Switching on the version at runtime](#switching-on-the-version-at-runtime) ·
[Design notes](#design-notes) · [Layout](#layout) · [Build](#build)

## Quick start

With Nix:

```sh
nix develop
./gradlew build run                                  # Python 3.12, the shell's python3
./gradlew run -Ppython.executable=python3.14         # any Python in the shell: 3.12 to 3.15
./gradlew test -Ppython.executable=python3.15
./gradlew jextract                                   # regenerate the bindings
```

Without Nix, you need what's listed under [Requirements](#requirements); then
the same `./gradlew` commands work.

## Requirements

| To…                              | You need                                                                  |
|----------------------------------|---------------------------------------------------------------------------|
| compile (`./gradlew assemble`)   | any JDK 17+ to launch Gradle; Gradle provisions JDK 25 if it isn't installed |
| run or test                      | Python 3.12 or later, built with a shared libpython (`--enable-shared`)   |
| regenerate the bindings          | [jextract](https://jdk.java.net/jextract/), and the headers of Python 3.12, 3.13, 3.14 and 3.15 (one per [binding](#the-bindings)) |

On Debian or Ubuntu, the runtime needs the `libpython3.X` package (the
versioned `libpython3.X.so.1.0` is enough). Regenerating needs the
`python3.X-dev` packages.

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

`flake.nix` provides a dev shell (Linux) with JDK 25, jextract, and Python
3.12 to 3.15. Python 3.12 is the shell's `python3`. The others are on the
`PATH` as `python3.13`, `python3.14` and `python3.15`, and also supply the
headers that `./gradlew jextract` needs.

The shell also uses
[nix-gradle-wrapper](https://github.com/devinrsmith/nix-gradle-wrapper):

- It copies the Gradle distribution pinned in `gradle-wrapper.properties` into
  the Nix store, so `./gradlew` never downloads Gradle.
- It points `GRADLE_USER_HOME` at an isolated directory
  (`~/.cache/ffm-proto-nix-gradle-home`) that limits Gradle to the shell's
  JDK 25. Gradle won't detect other JDKs on the host or download any.
- It runs the Gradle daemon itself on that JDK.

The shell sets `CPATH` to glibc's headers so jextract can find them (see
[The bindings](#the-bindings)). It also unsets the `PYTHONPATH`,
`PYTHONHASHSEED` and `PYTHONNOUSERSITE` variables that nixpkgs' Python setup
hook exports. `PYTHONPATH` in particular pointed at python312's site-packages,
and the embedded interpreter honors it like `python` does, so it ended up on a
3.15 interpreter's module search path.

## Using it

The public API is in `com.example.ffm.python`, the module's only exported
package:

```java
try (EmbeddedPython python = EmbeddedPython.start()) {
    python.exec("print('hello')");                    // runs in __main__
    PythonVersion version = python.version();         // the loaded library's Py_Version
    SequencedMap<String, String> config = python.configuration();
    InterpreterInfo info = python.interpreterInfo();
    Optional<Path> so = python.findExtensionModule("pkg._native");

    try (SubInterpreter sub = python.newSubInterpreterFromConfig(InterpreterConfig.isolated())) {
        sub.exec("print('in a sub-interpreter with its own GIL')");
    }
}
```

| `EmbeddedPython` | |
|---|---|
| `start()` | loads libpython ([as configured](#choosing-the-python-at-runtime)) and initializes the main interpreter |
| `exec(source)` | runs code in `__main__`, like `PyRun_SimpleString`; a Python exception becomes a `PythonException`, with the traceback printed to stderr |
| `version()`, `library()`, `executable()` | what was loaded, and the executable it presents itself as |
| `configuration()`, `interpreterInfo()` | [diagnostics](#what-the-demo-prints) |
| `findExtensionModule(name)` | [the file of an extension module, without importing it](#loading-an-extension-module-from-java-first) |
| `newSubInterpreter()`, `newSubInterpreterFromConfig(config)` | [sub-interpreters](#sub-interpreters) |
| `close()` | finalizes the interpreter |

`EmbeddedPython` is confined to the thread that started it. Sub-interpreters
can be created from any platform thread, and each is confined to its own.

## What the demo prints

A run in a Python 3.15 virtual environment, without jpy installed:

```console
$ ./gradlew run -q -Ppython.executable=/home/me/.venv/bin/python
Java: starting embedded Python
Java: embedding /nix/store/…-python3-3.15.0/lib/libpython3.15.so.1.0
Java: as /home/me/.venv/bin/python
Java: Py_Version = 0x030F00F0 = 3.15.0 (final)
Java: interpreter 0 (main) at 0x7fffc5337c08, thread state 1, interpreter dict 0 entries
Java: Python configuration:
  program_name        = /home/me/.venv/bin/python
  executable          = /home/me/.venv/bin/python
  home                = (not set)
  prefix              = /home/me/.venv
  exec_prefix         = /home/me/.venv
  module_search_paths = /nix/store/…-python3-3.15.0/lib/python315.zip:/nix/store/…-python3-3.15.0/lib/python3.15:/nix/store/…-python3-3.15.0/lib/python3.15/lib-dynload:/home/me/.venv/lib/python3.15/site-packages
  version             = 3.15.0 (main, Oct  9 2026, 11:16:41) [GCC 16.2.0]
  platform            = linux
  compiler            = [GCC 16.2.0]
  build_info          = main, Oct  9 2026, 11:16:41
Hello from Python 3.15.0, embedded in Java via FFM!
sys.prefix = /home/me/.venv
jpy_imported = False
Java: running 3 sub-interpreters on 3 threads
Java: [sub-interpreter 0, SHARED GIL] interpreter 1 at 0x7fffcc003228, thread state 1, interpreter dict 0 entries
Java: [sub-interpreter 1, OWN GIL] interpreter 2 at 0x7fffc800d698, thread state 1, interpreter dict 0 entries
[sub-interpreter 1] own sys module at 0x7fffc45136c0, native thread 102313, jpy_imported=False
[sub-interpreter 0] own sys module at 0x7fffc4aecdc0, native thread 102312, jpy_imported=False
Java: [sub-interpreter 2, OWN GIL] interpreter 3 at 0x7fffd0001d38, thread state 1, interpreter dict 0 entries
[sub-interpreter 2] own sys module at 0x7fffc420f6c0, native thread 102314, jpy_imported=False
Java: Python finalized
```

With jpy installed, `Main` also loads it. See [jpy](#example-jpy) for that
output.

**The version.** `EmbeddedPython.version()` returns the `Py_Version` runtime
constant as a `PythonVersion` record: `major`, `minor`, `micro`,
`releaseLevel` (`ALPHA`, `BETA`, `CANDIDATE` or `FINAL`, with Python's
`sys.version_info` names) and `releaseSerial`. Unlike the `PY_VERSION_HEX`
macro, which describes the headers code was compiled against (3.12 here),
`Py_Version` describes the library actually loaded. `PythonVersion` also packs
back to `hex()`, orders like the packed value (`3.14.0a1 < 3.14.0 < 3.14.1`),
and prints as Python does (`3.12.5rc2`). `LibPython` uses it for the 3.12
minimum check.

**The configuration** comes in two groups, keyed by name:

- **Path configuration:** `program_name`, `executable`, `home`, `prefix`,
  `exec_prefix` and `module_search_paths`. The names are `PyConfig_Get`'s, and
  the source depends on the loaded version (see [Switching on the version at
  runtime](#switching-on-the-version-at-runtime)):
  - **Up to 3.13:** the `Py_Get*()` functions (`Py_GetProgramName`,
    `Py_GetProgramFullPath`, `Py_GetPythonHome`, `Py_GetPrefix`,
    `Py_GetExecPrefix`, `Py_GetPath`). They were deprecated in 3.13 and removed
    from the 3.15 headers. As Stable ABI, 3.15 still exports them, but nothing
    promises they keep working, so they aren't used from 3.14 on.
  - **From 3.14:** their replacement, PEP 741's `PyConfig_Get`.
- **Build information:** `version`, `platform`, `compiler` and `build_info`,
  from `Py_GetVersion`, `Py_GetPlatform`, `Py_GetCompiler` and
  `Py_GetBuildInfo` on every version. These aren't deprecated.
  `Py_GetCopyright` returns a multi-line legal notice rather than
  configuration, so it isn't bound.

**Each version's values are reported as is, and two keys differ in meaning
between the sources.** The same run in a 3.12 venv reports:

```console
  prefix              = /nix/store/…-python3-3.12.15
  exec_prefix         = /nix/store/…-python3-3.12.15
  module_search_paths = /nix/store/…-python3-3.12.15/lib/python312.zip:/nix/store/…-python3-3.12.15/lib/python3.12:/nix/store/…-python3-3.12.15/lib/python3.12/lib-dynload
```

- **`module_search_paths`:** up to 3.13 this is `Py_GetPath()`, the search
  path computed at startup *before* `site` runs. From 3.14, `PyConfig_Get`
  returns the live `sys.path`, which includes what `site` added (here, the
  venv's site-packages). No replacement gives the old value exactly.
- **`prefix` and `exec_prefix` in a venv:** up to 3.13, `Py_GetPrefix()` and
  `Py_GetExecPrefix()` report the venv's *base* installation, even though
  `sys.prefix` is the venv. From 3.14 they're the venv.
- `program_name` and `executable` name the venv's `python` on every version,
  so use those (or `sys.prefix`) to tell whether a venv is in effect.

The test asserts both differences per version. Forcing the old functions on
3.14 makes it fail.

**The interpreter diagnostics.** `interpreterInfo()`, on either
`EmbeddedPython` or `SubInterpreter`, returns an `InterpreterInfo` record. It's
read while attached to that interpreter, from the `PyInterpreterState_Get*()`
family:

| Component       | From                                                                         |
|-----------------|------------------------------------------------------------------------------|
| `id`            | `PyInterpreterState_GetID()`: `0` for the main interpreter, never reused     |
| `main`          | `PyInterpreterState_Get() == PyInterpreterState_Main()`                      |
| `stateAddress`  | `PyInterpreterState_Get()`, the `PyInterpreterState *`; only unique among interpreters alive at the same time, because CPython reuses the memory |
| `threadStateId` | `PyThreadState_GetID()`, numbered *per interpreter* (so usually `1`)         |
| `dictSize`      | entries in `PyInterpreterState_GetDict()`, where extensions keep per-interpreter data (`PyDict_Size`). CPython creates the dict on first request, so reading it allocates it |

IDs are assigned in creation order, which need not match the order your
threads start in. Use `id`, not `stateAddress`, to tell interpreters apart over
time: four sub-interpreters created one after another all got the same
address. `PyInterpreterState_Head`/`Next`, which list every live
interpreter, are left out: CPython documents them as unsafe while other
threads create or end interpreters.

## Loading an extension module from Java first

Some Python extension modules must be loaded by the JVM before Python imports
them. For example, a module that also implements JNI native methods must be
`System.load`ed, so those methods bind to the right class loader.
`EmbeddedPython.findExtensionModule(name)` finds the module's file, like
`importlib.util.find_spec(name).origin`, but **without importing anything**:

```java
try (EmbeddedPython python = EmbeddedPython.start()) {
    Path so = python.findExtensionModule("pkg._native").orElseThrow();
    System.load(so.toString());
    python.exec("import pkg");  // Python's import reuses the library Java loaded
}
```

- **Why not just call `find_spec`:** for a dotted name, `find_spec` *imports
  the parent packages*, running their `__init__.py`, which typically imports
  the very module you're trying to load first. Instead, `ModuleFinder`
  resolves `a.b.c` one level at a time. At each level it asks every finder on
  `sys.meta_path`, as `importlib` does (`finder.find_spec(name, path, target)`),
  passing the previous level's `submodule_search_locations`. Using
  `sys.meta_path` rather than only `PathFinder` also covers custom finders,
  such as those editable installs register.
- **Python reuses Java's library:** Python's import `dlopen`s the same file,
  and glibc returns the already-loaded library (matching by device and inode),
  so the module's native state is shared. The test checks that the file is
  mapped only once.
- **Results:** empty if no finder finds the module. `IllegalArgumentException`
  if the module is found but isn't an extension module (`json` →
  `SourceFileLoader`, `sys` → `BuiltinImporter`).
- **Requirements:**
  - **libpython must already be global.** At `System.load` time, the
    extension's `Py*` symbols must resolve, and they do because `LibPython`
    re-opens libpython with `RTLD_GLOBAL` at startup (see
    [Extension modules](#extension-modules)).
  - **One class loader per library.** Java ties a native library to one class
    loader; loading the same file from another throws `UnsatisfiedLinkError`.
  - **Timing.** Call it after `start()` but before any code imports the
    module.

It's built on Stable ABI functions, typed as usual: `Module.importModule`,
`getAttr`, `hasAttr`, `call` (`PyObject_Vectorcall`), `isInstance`, `isNone`,
`Str.of`, and `Any.none()`. `None` is exported data (`_Py_NoneStruct`), so it's
found by symbol lookup like the built-in types. While a finder runs, it's held
with its own reference, since it's borrowed from `sys.meta_path` and the
finder's own code could change that list.

<a id="example-jpy"></a>**Example: jpy.** `Main` does this for
[jpy](https://github.com/jpy-consortium/jpy), a Python–Java bridge whose
native library is both a Python extension module and a JNI library. With jpy
2.1.0 (from PyPI) installed in a 3.12 venv:

```console
$ ./gradlew run -q -Ppython.executable=/home/me/jpy-venv/bin/python
…
Java: Loading found jpy extension: /home/me/jpy-venv/lib/python3.12/site-packages/jpy.cpython-312-x86_64-linux-gnu.so
Hello from Python 3.12.15, embedded in Java via FFM!
sys.prefix = /home/me/jpy-venv
jpy_imported = True
…
[sub-interpreter 0] own sys module at 0x7fffc49a3e70, native thread 102398, jpy_imported=True
[sub-interpreter 2] own sys module at 0x7fffc448e750, native thread 102400, jpy_imported=False
[sub-interpreter 1] own sys module at 0x7fffc428a750, native thread 102399, jpy_imported=False
```

- **jpy connects to the embedding JVM.** jpy's library exports 57 JNI native
  methods (`Java_org_jpy_PyLib_*`) and a `JNI_OnLoad`. The `System.load` runs
  `JNI_OnLoad`, which hands jpy the running JVM. From the embedded Python,
  `jpy.has_jvm()` is `True`, and
  `jpy.get_type("java.lang.System").getProperty("java.version")` returned the
  version of the JVM running `Main` (`25.0.4.1`).
- **It only loads inside a JVM.** jpy's library depends on `libjvm.so`, with a
  search path pointing at the wheel builder's JDK
  (`/usr/lib/jvm/temurin-11-jdk-amd64/lib/server`). Inside the JVM, the
  dependency is satisfied by the `libjvm.so` already in the process. A
  standalone `python -c "import jpy"` on this machine would fail to load it.
- **Sub-interpreters:**
  - **Isolated sub-interpreters refuse jpy:** `module jpy does not support
    loading in subinterpreters`. It uses the older single-phase module
    initialization.
  - **The shared-GIL sub-interpreter imports it, but don't use it there.** A
    single-phase module keeps its native state in C globals shared by every
    interpreter, so its objects could cross interpreters, which isn't safe.
- **Not tested:** jpy's own Java classes (`org.jpy.PyLib`). They weren't on the
  classpath, so this example only exercises the Python side of jpy.

## Sub-interpreters

There are two ways to create a sub-interpreter. Each sub-interpreter has its
own modules, `sys` and `__main__`. The difference is whether it shares the
main interpreter's GIL:

| | `newSubInterpreter()` | `newSubInterpreterFromConfig(config)` |
|---|---|---|
| C API | `Py_NewInterpreter()`, **Stable ABI** | `Py_NewInterpreterFromConfig()`, version-specific (`capi.py312`) |
| Configuration | always `InterpreterConfig.legacy()` | any valid `InterpreterConfig` |
| GIL | **shared** with the main interpreter: threads take turns | with `isolated()`: **its own**, so it runs in parallel |
| Allows | everything: single-phase extensions, fork, exec, daemon threads | what the config allows; `isolated()` forbids all four |

```java
try (EmbeddedPython python = EmbeddedPython.start()) {
    // On any platform thread:
    try (SubInterpreter shared = python.newSubInterpreter();
         SubInterpreter parallel = python.newSubInterpreterFromConfig(InterpreterConfig.isolated());
         SubInterpreter custom = python.newSubInterpreterFromConfig(
                 InterpreterConfig.isolated().withAllowThreads(false))) {
        parallel.exec("import math; print(math.pi)");
    }
}
```

The tests confirm the GIL difference by timing two CPU-bound loops started
together on two threads. With their own GILs they take about as long as one
loop (about 1.0×). Sharing the GIL, they take twice as long (about 2.0–2.2×).

**`InterpreterConfig`** mirrors CPython's `PyInterpreterConfig` field for
field: `useMainObmalloc`, `allowFork`, `allowExec`, `allowThreads`,
`allowDaemonThreads`, `checkMultiInterpExtensions` and `gil` (`SHARED` or
`OWN`). Start from `isolated()` (CPython's `_PyInterpreterConfig_INIT`) or
`legacy()` (`_PyInterpreterConfig_LEGACY_INIT`), and adjust with the `with*`
methods. Its constructor rejects, with an `IllegalArgumentException`, the two
combinations CPython doesn't support:

- **Its own obmalloc without `checkMultiInterpExtensions`.** CPython rejects
  this itself, but on 3.12 the rejection leaves the calling thread's GIL
  silently released, and the next GIL operation aborts the process. On
  3.13–3.15 it fails cleanly. I found this by probing every combination
  against each version. So the Java side must never let CPython see a
  configuration it would reject.
- **Its own GIL with the main obmalloc.** CPython's documentation forbids this,
  since two GILs sharing one allocator would race, but 3.13–3.15 accept it
  without complaint.

So order matters when adjusting:
`isolated().withGil(SHARED).withUseMainObmalloc(true)` is valid, while
`isolated().withUseMainObmalloc(true)` is not.

The rules, all enforced:

- **Confined to its creating thread.** CPython ties a sub-interpreter's thread
  state to that OS thread. For the same reason, virtual threads are rejected,
  since they can move between OS threads.
- **Close sub-interpreters before the main interpreter.**
  `EmbeddedPython.close()` refuses while any are open, because CPython 3.12
  can't finalize around live sub-interpreters.
- **Some extension modules can't be imported** in sub-interpreters with
  `checkMultiInterpExtensions` (as in `isolated()`; legacy sub-interpreters
  import them all). Those accept only extension modules that support
  multi-phase initialization. That covers most of the stdlib (`math`, `json`,
  `zlib`, `_sqlite3`, `_ssl`, …). Every version refuses `readline` and
  `_curses`, and 3.12 also refuses `_ctypes`, `_decimal` and `_datetime`. A
  refused import is an ordinary `ImportError`, which `exec` throws as a
  `PythonException`.

**How creation works.** Creating a sub-interpreter briefly attaches the
calling thread to the main interpreter with `PyGILState_Ensure`, as both C
functions require, and detaches again before returning. Both functions make the
new thread state current. Before detaching it, `EmbeddedPython` checks that the
thread state the function returned is the current one and isn't the main
interpreter's. `exec` attaches the sub-interpreter's own thread state. With its
own GIL, running code never contends for the main GIL.

## The bindings

The code under `src/main/jextract` was generated by jextract and is committed.
That means an ordinary build needs neither jextract nor C headers, and the
bindings can be reviewed in diffs. `./gradlew jextract` regenerates them, by
running seven tasks:

| Task | Symbols file | Generates | Headers |
|---|---|---|---|
| `jextractStable312` | `stable312.symbols` | `capi.stable312.Stable312` | 3.12, `Py_LIMITED_API=0x030C0000` |
| `jextractStable313` | `stable313.symbols` | `capi.stable313.Stable313` | 3.13, `Py_LIMITED_API=0x030D0000` |
| `jextractStable314` | `stable314.symbols` | `capi.stable314.Stable314` | 3.14, `Py_LIMITED_API=0x030E0000` |
| `jextractStable315` | `stable315.symbols` | `capi.stable315.Stable315` | 3.15, `Py_LIMITED_API=0x030F0000` |
| `jextractCPython312` | `cpython312.symbols` | `capi.py312.CPython312` | 3.12, full API |
| `jextractCPython314` | `cpython314.symbols` | `capi.py314.CPython314` | 3.14, full API |
| `jextractDlfcn` | `dlfcn.symbols` | `dlfcn.Dlfcn` | the C library's `<dlfcn.h>` |

- **Only what's listed:** each task generates only the symbols listed in its
  jextract argument file (in `jextract/`), into its own package. To bind more
  of the C API, add lines to the right file.
- **Exactly the version in its name:** each Python task uses the headers of
  exactly that version, from the interpreters in the Gradle properties
  `jextract.python312` to `jextract.python315` (`python3.12` and so on by
  default, in `gradle.properties`). It fails unless it gets that version, so no
  binding can be silently regenerated from other headers.
- **Every requested symbol must be generated:** jextract skips a requested
  symbol it can't bind. It's silent when the symbol isn't in the header view
  (absent, or hidden by `Py_LIMITED_API`), and it only warns, among many other
  warnings, when it can't represent the symbol's type. So each task checks
  that every `--include-*` line in its symbols file produced a declaration in
  the generated code, and fails otherwise, before touching the committed
  bindings:

  ```
  > jextract did not generate 2 requested symbol(s) from stable312.symbols:
        --include-function PyMem_RawFree
        --include-var PyUnicode_Type
      Possible causes: not in the headers, hidden by Py_LIMITED_API=0x030C0000, or a type jextract cannot represent (look for "Skipping <name>" in its output above).
  ```

**`jextractStable312`** covers almost everything. It extracts `<Python.h>` with
`Py_LIMITED_API=0x030C0000` defined, so CPython exposes only the Stable ABI as
of 3.12: the functions it promises to keep in every later 3.x. So a symbol
jextract can't find is one that newer Pythons wouldn't guarantee anyway. For
example, `PyRun_SimpleString` isn't in the Stable ABI, so `EmbeddedPython.exec`
uses `Py_CompileString` and `PyEval_EvalCode` instead. To list everything on
offer:

```sh
jextract -D Py_LIMITED_API=0x030C0000 -I <include dir> --dump-includes all.symbols '<Python.h>'
```

Function-like macros can't be extracted. Bind the function behind them
instead, for example `Py_DecRef` for `Py_DECREF`.

**`jextractStable313`, `jextractStable314` and `jextractStable315`** bind only
what the Stable ABI added at those levels: `PyImport_AddModuleRef` (3.13),
`Py_TYPE` (3.14) and `Py_IS_TYPE` (3.15). Older libpythons don't export these,
so they're only called behind a version check. See [Switching on the version at
runtime](#switching-on-the-version-at-runtime).

**`jextractCPython312`** generates from `<Python.h>` *without*
`Py_LIMITED_API`, for what the Stable ABI lacks:
[sub-interpreters with an explicit configuration](#sub-interpreters), and
`PyInterpreterState_Main()` to identify the main interpreter. Nothing here is
covered by CPython's ABI promises, so the version is in every name: the
symbols file, the task, the package (`py312`) and the class (`CPython312`,
which is what shows at call sites, since they use static imports). Only
symbols whose declarations and struct layouts are unchanged in every supported
version are bound: `PyInterpreterConfig` and `PyStatus` are identical from
3.12 through 3.15. A future Python that changes them would need a `py3XX`
package of its own, chosen at runtime by `Py_Version`.

**`jextractCPython314`** binds PEP 741's `PyConfig_Get` from the full 3.14
headers, by the same rules. Its declaration is identical in 3.14 and 3.15. It
doesn't exist before 3.14, so it's only called behind a version check.

**`jextractDlfcn`** binds `dlopen`, `dlerror` and `RTLD_*` from the C library's
`<dlfcn.h>`. `LibPython` uses them to make libpython's symbols global (see
[Extension modules](#extension-modules)). It involves no Python headers, so it
has no Python version to check. The functions come from the C library through
the linker's default lookup.

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
`LIBCLANG_DISABLE_CRASH_RECOVERY=1` helped. The task writes into a staging
directory and syncs into `src/main/jextract` only on success, so a crash never
damages the committed bindings.

## Switching on the version at runtime

The bindings target the oldest supported version (3.12), but newer CPython
versions add better functions. Where that matters, the typed wrapper in the
`internal` package chooses at runtime, based on the loaded library's
`Py_Version` (`ApiLevel.atLeast(3, 13)`, read once). There are three kinds of
case.

**A newer, safer function.** `PyObject.Module.importAddRef` always returns a
*new* reference to a module from `sys.modules`:

- **3.13+** use `PyImport_AddModuleRef`, which CPython added because the old
  function's borrowed result could be freed by any Python code.
- **3.12** uses `PyImport_AddModule` and takes the reference itself with
  `Py_IncRef`.

Callers get the same `NewReference` either way.

**A deprecated function and its replacement.** The [configuration
diagnostics](#what-the-demo-prints) come from the deprecated `Py_Get*()` path
functions up to 3.13, and from `PyConfig_Get` (`capi.py314`) from 3.14. Its
`str` / `None` / `list` results are read through typed `PyObject.Str` and
`PyObject.List` records. The two sources disagree on what two keys mean; this
is documented there and asserted per version.

**A header inline that was later exported.** Some of CPython's API is
`static inline` code in the headers. C callers compile it into their own code,
but it isn't an exported function, so FFM can't call it. CPython has been
turning some of these into real exported functions in the Stable ABI, for code
that can't inline them:

| Function | Header inline only | Exported since | Binding | Fallback before that |
|---|---|---|---|---|
| `Py_TYPE(o)` | 3.12–3.13 | 3.14 | `capi.stable314` | `PyObject_Type(o)` + `Py_DecRef`: the same type, as a borrowed reference that the object keeps alive |
| `Py_IS_TYPE(o, t)` | 3.12–3.14 | 3.15 | `capi.stable315` | `Py_TYPE(o) == t`, the inline's own body |

`internal.HeaderInlines` holds one method per row. Each calls the exported
function where the loaded libpython has it, and the fallback before that.
`PyObject.type()` and `PyObject.isType(Type)` use them. A test checks that each
exported function agrees with its fallback on the versions where both exist.
The fallbacks deliberately avoid what the inlines themselves do, reading
`ob->ob_type`. That would tie the bindings to the `PyObject` struct layout,
which is what CPython made these functions to move away from (for
free-threading).

**Checked, not assumed.** Each version takes the path described above. 3.12
uses every fallback. 3.13 uses `PyImport_AddModuleRef`. 3.14 also uses
`Py_TYPE` and `PyConfig_Get`. 3.15 also uses `Py_IS_TYPE`. Temporary probes
showed this for `importAddRef` and the header inlines; in each case the native
path coincided with the loaded library exporting the function. For
`PyConfig_Get`, the configuration test shows it: forcing the old functions on
3.14 makes the test fail. The test matrix runs every path. The refcount
test catches ownership mistakes on either side of `importAddRef`: an extra
reference on the 3.13 path made it fail (5 → 106), and so does a missing one
on 3.12 (the `__main__` test).

**This relies on lazy symbol lookup.** A 3.12 libpython doesn't export
`PyImport_AddModuleRef`, but jextract's generated code looks each function up
only on its first call, so the binding is harmless until called. So every call
into `capi.stable313`, `capi.stable314`, `capi.stable315` or `capi.py314` (or
any future newer-level binding) must sit behind an `ApiLevel` check.

**Types.** `PyObject.Type` wraps a type object. `Type.unicode()`, `list()`,
`dict()`, `module()` and `typeType()` are the built-in types (`PyUnicode_Type`
and so on). They've been part of the Stable ABI as *data* since 3.2, and every
supported version exports them. The Stable ABI covers their addresses, not
their contents: under `Py_LIMITED_API`, `PyTypeObject` is an incomplete struct,
so Limited-API C code can only take `&PyUnicode_Type`. For the same reason,
jextract skips them, with a warning that's easy to miss among its many others:
`Skipping PyUnicode_Type (type Declared(_typeobject) is not supported)`. So
their addresses come from a symbol lookup, which yields exactly
`&PyUnicode_Type`, without binding the version-specific `PyTypeObject` layout.

`Type.flags()` is `PyType_GetFlags`. `Any.asStr()` and `asList()` are exact
type checks, as C's `PyUnicode_CheckExact` and `PyList_CheckExact` (subclasses
don't match). For a subclass-aware check, as `PyUnicode_Check`, use the type's
flags.

## Design notes

- **A typed layer over the generated bindings.** Outside the `internal`
  package, no code calls a CPython function that takes or returns an object,
  thread state or interpreter state directly. Each Stable ABI function lives on
  the record it applies to, e.g. `module.dict()`, `code.eval(globals, locals)`,
  `dict.size()`, `object.getAttr(name)`, `threadState.attach()`,
  `interpreter.id()`, with static factories such as
  `PyObject.Code.compile(...)` and `PyThreadState.current()`. The records use
  only the Stable ABI. The version-specific functions stay apart, in `Py312`
  and `Py314`.
  - **Objects:** `PyObject` is a sealed interface over `PyObject.Any`, `Code`,
    `Dict`, `List`, `Module`, `Str` and `Type`. A **bare record is a borrowed
    reference**: usable, never released. A **`NewReference<T>` is an owned
    one**, released with `Py_DecRef` exactly once on `close()`, and unusable
    after that. Each wrapper returns whichever CPython documents for that
    function, so try-with-resources enforces the decRefs.
    `NewReference.incRef(borrowed)` makes an owned reference from a borrowed
    one:

    ```java
    try (NewReference<PyObject.Module> main = PyObject.Module.importAddRef("__main__")) {
        PyObject.Dict globals = main.get().dict();  // borrowed from main
        try (NewReference<PyObject.Code> code = PyObject.Code.compile(source, "<string>")) {
            code.get().eval(globals, globals).close();
        }
    }
    ```
  - **Borrowed or new:** a wrapper returns a borrowed reference only when it's
    borrowed from something the caller already holds and Python code can't
    take away, such as `module.dict()`. A reference borrowed from a container
    any Python code can change, such as `sys.modules`, is returned as a
    `NewReference`, as CPython's own 3.13 `...Ref` functions do.
  - **States:** thread states, interpreter states and the
    `PyGILState_Ensure` token get records too (`PyThreadState`,
    `PyInterpreterState`, `GilState`). They aren't refcounted, but a main
    thread state can no longer be confused with a sub-interpreter's, or a GIL
    token with any other `int`. `threadState.runAttached(...)` and
    `callAttached(...)` attach for the duration of an action and always detach
    afterwards, so no call site can forget the `finally`.
  - **Errors:** records reject `NULL`. Wrappers turn CPython's "`NULL` plus an
    exception" into a `PythonException`, after printing and clearing the
    exception.
  - **What isn't checked:** a record's type is what CPython's documentation
    promises for that function; it isn't verified at runtime.
  - **Tests:** a test runs `exec` 100 times and checks that `__main__`'s
    reference count is unchanged. With releasing disabled, it fails with the
    count going from 4 to 105.
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
  unexpected ways (`Main` flushes explicitly).
- **Memory.** Two kinds of native memory are in play, managed differently:
  - **Memory we allocate** (C strings, `PyInterpreterConfig`, `PyStatus`,
    out-parameters, call argument arrays) lives in a confined `Arena` and is
    freed when the call returns. None of it is refcounted, and CPython copies
    what it keeps. The one exception is the `Py_SetProgramName` string, which
    must outlive the interpreter and so lives in `Arena.global()`.
  - **Python objects** are refcounted (see the typed layer above). `__main__`
    was the case that needed care: it's borrowed from `sys.modules`, which the
    code being run can change (`del sys.modules["__main__"]` frees it mid-run).
    So `exec` holds a strong reference for the duration, via `importAddRef`. A
    test removes `__main__` mid-run and checks, via a weak reference, that it
    isn't freed. It fails without the fix.
- **`SystemExit`.** As with `PyRun_SimpleString`, an uncaught `SystemExit` in
  `exec` reaches `PyErr_Print`, which exits the process, JVM included.

## Layout

```
build-logic/                        included build: JextractTask, its plugin, PATH lookup
flake.nix                           Nix dev shell (JDK 25, jextract, Python 3.12-3.15, vendored Gradle)
gradle/libs.versions.toml           version catalog
jextract/                           jextract argument files: which symbols each task binds
  stable312.symbols                   Stable ABI, 3.12 level
  stable313.symbols, stable314.symbols, stable315.symbols
                                      ...what the Stable ABI added in 3.13, 3.14, 3.15
  cpython312.symbols, cpython314.symbols
                                      CPython's version-specific API, from 3.12 / 3.14 headers
  dlfcn.symbols                       <dlfcn.h> (the C library, not Python)
src/main/jextract/                  generated bindings (committed, do not edit)
  .../capi/stable312/                 Stable312
  .../capi/stable313/, stable314/, stable315/
                                      Stable313, Stable314, Stable315
  .../capi/py312/, py314/             CPython312, CPython314
  .../dlfcn/                          Dlfcn
src/main/java/                      module com.example.ffm.python
  com/example/ffm/python/           the public API, plus Main
    EmbeddedPython.java               the main interpreter
    SubInterpreter.java               a sub-interpreter (shared or own GIL)
    InterpreterConfig.java            PyInterpreterConfig, validated
    InterpreterInfo.java              PyInterpreterState_Get*() diagnostics
    PythonVersion.java                Py_Version / PY_VERSION_HEX, unpacked
    PythonException.java              a Python exception, as a Java one
    LibPython.java                    finds, loads and checks libpython (package-private)
    PythonCode.java                   runs source in the current interpreter (package-private)
    Main.java                         the demo
  com/example/ffm/python/internal/  the typed layer over the bindings (not exported)
    PyObject.java                     sealed: Any, Code, Dict, List, Module, Str, Type, with their functions
    NewReference.java                 an owned reference: Py_DecRef on close
    PyThreadState.java, PyInterpreterState.java, GilState.java
                                      thread and interpreter states, with their functions
    Py312.java, Py314.java            the version-specific functions
    ApiLevel.java                     the loaded version, for runtime dispatch
    HeaderInlines.java                header inlines later exported, with fallbacks
    Configuration.java                the configuration diagnostics
    ModuleFinder.java                 find_spec without importing
    Py.java, WideChars.java           shared plumbing: refcounting, errors, wchar_t
src/test/java/                      tests (each class in its own JVM)
```

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
