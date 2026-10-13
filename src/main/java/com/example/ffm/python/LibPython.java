package com.example.ffm.python;

import static com.example.ffm.python.capi.stable312.Stable312.Py_Version;
import static com.example.ffm.python.dlfcn.Dlfcn.RTLD_GLOBAL;
import static com.example.ffm.python.dlfcn.Dlfcn.RTLD_NOLOAD;
import static com.example.ffm.python.dlfcn.Dlfcn.RTLD_NOW;
import static com.example.ffm.python.dlfcn.Dlfcn.dlerror;
import static com.example.ffm.python.dlfcn.Dlfcn.dlopen;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finds and loads the libpython shared library to embed.
 *
 * <p>The bindings are generated from the 3.12 headers but restricted to the Stable
 * ABI, so any libpython 3.12 or later works. Which one is chosen at runtime:
 *
 * <ol>
 *   <li>{@code -Dpython.library=/path/to/libpython3.X.so}, if set; otherwise
 *   <li>the shared library of the interpreter {@code -Dpython.executable} (default
 *       {@code python3}, looked up on the {@code PATH}), as reported by its
 *       {@code sysconfig}.
 * </ol>
 *
 * <p>In the second case the interpreter's {@code sys.executable} is reported too, so
 * that the embedded interpreter can present itself as that executable; this is what
 * makes it pick up a virtual environment.
 */
final class LibPython {

    static final String LIBRARY_PROPERTY = "python.library";
    static final String EXECUTABLE_PROPERTY = "python.executable";
    static final String DEFAULT_EXECUTABLE = "python3";

    /** The oldest supported version: the one the bindings are generated from. */
    static final PythonVersion MINIMUM_VERSION = PythonVersion.of(3, 12, 0);

    /**
     * Prints the path of the interpreter's shared libpython, then its executable.
     * Prefers the versioned soname (e.g. libpython3.12.so.1.0): distros such as
     * Debian only ship the unversioned libpython3.12.so symlink in their -dev
     * packages. In a virtual environment, sysconfig reports the base installation's
     * library, while sys.executable is the environment's own python.
     */
    private static final String SYSCONFIG_QUERY = """
            import os, sys, sysconfig
            var = sysconfig.get_config_var
            if not var("Py_ENABLE_SHARED"):
                sys.exit(f"{sys.executable} was built without a shared libpython (--enable-shared)")
            libdir = var("LIBDIR")
            for name in (var("INSTSONAME"), var("LDLIBRARY")):
                if name and os.path.isfile(os.path.join(libdir, name)):
                    print(os.path.join(libdir, name))
                    print(sys.executable)
                    break
            else:
                sys.exit(f"no shared libpython for {sys.executable} found in {libdir}")
            """;

    /**
     * The libpython to embed.
     *
     * @param library the shared library
     * @param executable the interpreter it belongs to, if known
     */
    record Selection(Path library, Optional<Path> executable) {}

    private static Selection loaded;

    private LibPython() {}

    /** Loads libpython once per JVM, returning what was loaded. */
    static synchronized Selection load() {
        if (loaded == null) {
            Selection selection = locate();
            Path library = selection.library();
            // Makes the library visible to the generated bindings' SymbolLookup.loaderLookup().
            System.load(library.toString());
            exportSymbols(library);
            checkVersion(library);
            loaded = selection;
        }
        return loaded;
    }

    private static Selection locate() {
        String explicit = System.getProperty(LIBRARY_PROPERTY);
        if (explicit != null) {
            Path library = Path.of(explicit).toAbsolutePath();
            if (!Files.isRegularFile(library)) {
                throw new IllegalStateException(LIBRARY_PROPERTY + " does not name a file: " + library);
            }
            return new Selection(library, Optional.empty());
        }
        return askInterpreter(System.getProperty(EXECUTABLE_PROPERTY, DEFAULT_EXECUTABLE));
    }

    private static Selection askInterpreter(String executable) {
        // -I (isolated mode): ignore PYTHON* environment variables and user site-packages.
        ProcessBuilder builder = new ProcessBuilder(executable, "-I", "-c", SYSCONFIG_QUERY)
                .redirectErrorStream(true);
        String output;
        int exitCode;
        try {
            Process process = builder.start();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out asking " + executable + " for its libpython");
            }
            exitCode = process.exitValue();
        } catch (IOException e) {
            throw new IllegalStateException("Could not run " + executable + " to locate libpython; set -D"
                    + EXECUTABLE_PROPERTY + " or -D" + LIBRARY_PROPERTY, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while locating libpython", e);
        }
        if (exitCode != 0) {
            throw new IllegalStateException("Could not locate libpython using " + executable + ": " + output);
        }
        // The last two lines; anything before them would be stray interpreter output.
        List<String> lines = output.lines().toList();
        if (lines.size() < 2) {
            throw new IllegalStateException("Unexpected output from " + executable + ": " + output);
        }
        return new Selection(
                Path.of(lines.get(lines.size() - 2)), Optional.of(Path.of(lines.get(lines.size() - 1))));
    }

    /**
     * Makes libpython's symbols globally visible.
     *
     * <p>{@code System.load} opens the library with {@code RTLD_LOCAL}. C extension
     * modules such as {@code math} expect to resolve symbols like
     * {@code PyExc_StopIteration} from the process's global namespace, so without
     * this even {@code import math} fails with "undefined symbol". Re-opening the
     * already-loaded library with {@code RTLD_GLOBAL} fixes that.
     */
    private static void exportSymbols(Path library) {
        try (Arena arena = Arena.ofConfined()) {
            // RTLD_NOLOAD: only promote the existing mapping, never load a second copy.
            MemorySegment handle = dlopen(arena.allocateFrom(library.toString()), RTLD_NOW() | RTLD_NOLOAD() | RTLD_GLOBAL());
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("dlopen(" + library + ") failed: " + dlerror().getString(0));
            }
        }
    }

    private static void checkVersion(Path library) {
        // Py_Version itself only exists from 3.11 on.
        if (SymbolLookup.loaderLookup().find("Py_Version").isEmpty()) {
            throw new IllegalStateException(
                    library + " is older than Python 3.11; Python " + MINIMUM_VERSION + " or later is required");
        }
        PythonVersion version = PythonVersion.fromHex(Py_Version());
        // 3.12 pre-releases count as 3.12: compare major.minor only.
        if (version.major() < MINIMUM_VERSION.major()
                || (version.major() == MINIMUM_VERSION.major() && version.minor() < MINIMUM_VERSION.minor())) {
            throw new IllegalStateException(
                    library + " is Python " + version + "; Python " + MINIMUM_VERSION + " or later is required");
        }
    }

    /** The version of the loaded libpython, from its {@code Py_Version} constant. */
    static PythonVersion version() {
        load();
        return PythonVersion.fromHex(Py_Version());
    }
}
