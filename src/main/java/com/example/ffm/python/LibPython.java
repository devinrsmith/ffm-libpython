package com.example.ffm.python;

import static com.example.ffm.python.capi.CApi.Py_Version;
import static com.example.ffm.python.capi.CApi.RTLD_GLOBAL;
import static com.example.ffm.python.capi.CApi.RTLD_NOLOAD;
import static com.example.ffm.python.capi.CApi.RTLD_NOW;
import static com.example.ffm.python.capi.CApi.dlerror;
import static com.example.ffm.python.capi.CApi.dlopen;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
final class LibPython {

    static final String LIBRARY_PROPERTY = "python.library";
    static final String EXECUTABLE_PROPERTY = "python.executable";
    static final String DEFAULT_EXECUTABLE = "python3";

    /** The oldest supported version, in {@code PY_VERSION_HEX} format: 3.12. */
    private static final long MINIMUM_VERSION = 0x030C0000L;

    /**
     * Prints the path of the interpreter's shared libpython. Prefers the versioned
     * soname (e.g. libpython3.12.so.1.0): distros such as Debian only ship the
     * unversioned libpython3.12.so symlink in their -dev packages.
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
                    break
            else:
                sys.exit(f"no shared libpython for {sys.executable} found in {libdir}")
            """;

    private static Path loaded;

    private LibPython() {}

    /** Loads libpython once per JVM, returning its path. */
    static synchronized Path load() {
        if (loaded == null) {
            Path library = locate();
            // Makes the library visible to the generated bindings' SymbolLookup.loaderLookup().
            System.load(library.toString());
            exportSymbols(library);
            checkVersion(library);
            loaded = library;
        }
        return loaded;
    }

    private static Path locate() {
        String explicit = System.getProperty(LIBRARY_PROPERTY);
        if (explicit != null) {
            Path library = Path.of(explicit).toAbsolutePath();
            if (!Files.isRegularFile(library)) {
                throw new IllegalStateException(LIBRARY_PROPERTY + " does not name a file: " + library);
            }
            return library;
        }
        return askInterpreter(System.getProperty(EXECUTABLE_PROPERTY, DEFAULT_EXECUTABLE));
    }

    private static Path askInterpreter(String executable) {
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
        return Path.of(output.lines().reduce((first, second) -> second).orElseThrow());
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
                    library + " is older than Python 3.11; Python " + format(MINIMUM_VERSION) + " or later is required");
        }
        long version = Py_Version();
        if (version < MINIMUM_VERSION) {
            throw new IllegalStateException(
                    library + " is Python " + format(version) + "; Python " + format(MINIMUM_VERSION) + " or later is required");
        }
    }

    /** Formats a {@code PY_VERSION_HEX} value as major.minor.micro. */
    static String format(long versionHex) {
        return (versionHex >>> 24 & 0xFF) + "." + (versionHex >>> 16 & 0xFF) + "." + (versionHex >>> 8 & 0xFF);
    }
}
