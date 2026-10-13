package com.example.ffm.python;

import static com.example.ffm.python.capi.CApi.PyErr_Print;
import static com.example.ffm.python.capi.CApi.PyEval_EvalCode;
import static com.example.ffm.python.capi.CApi.PyImport_AddModule;
import static com.example.ffm.python.capi.CApi.PyModule_GetDict;
import static com.example.ffm.python.capi.CApi.Py_CompileString;
import static com.example.ffm.python.capi.CApi.Py_DecRef;
import static com.example.ffm.python.capi.CApi.Py_FinalizeEx;
import static com.example.ffm.python.capi.CApi.Py_GetBuildInfo;
import static com.example.ffm.python.capi.CApi.Py_GetCompiler;
import static com.example.ffm.python.capi.CApi.Py_GetExecPrefix;
import static com.example.ffm.python.capi.CApi.Py_GetPath;
import static com.example.ffm.python.capi.CApi.Py_GetPlatform;
import static com.example.ffm.python.capi.CApi.Py_GetPrefix;
import static com.example.ffm.python.capi.CApi.Py_GetProgramFullPath;
import static com.example.ffm.python.capi.CApi.Py_GetProgramName;
import static com.example.ffm.python.capi.CApi.Py_GetPythonHome;
import static com.example.ffm.python.capi.CApi.Py_GetVersion;
import static com.example.ffm.python.capi.CApi.Py_InitializeEx;
import static com.example.ffm.python.capi.CApi.Py_IsInitialized;
import static com.example.ffm.python.capi.CApi.Py_SetProgramName;
import static com.example.ffm.python.capi.CApi.Py_file_input;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * An embedded CPython interpreter, valid from {@link #start()} until {@link #close()}.
 *
 * <p>CPython has one interpreter per process, and the thread that initializes it
 * holds the GIL. This class is therefore confined to the thread that started it;
 * calls from other threads would need {@code PyGILState_Ensure}/{@code Release}.
 *
 * <p>Python writes to the process's native stdout/stderr, not to {@link System#out},
 * and buffers that output: when stdout is not a terminal, it may only appear once
 * the interpreter is finalized.
 *
 * <p>Which libpython is embedded is decided at runtime; see {@link LibPython}.
 */
public final class EmbeddedPython implements AutoCloseable {

    /** {@code wchar_t} strings: 4-byte UTF-32 in native byte order on Linux. */
    private static final Charset WCHAR_CHARSET = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            ? Charset.forName("UTF-32LE")
            : Charset.forName("UTF-32BE");

    private final LibPython.Selection selection;
    private final Thread owner;
    private boolean closed;

    private EmbeddedPython(LibPython.Selection selection) {
        this.selection = selection;
        this.owner = Thread.currentThread();
    }

    /**
     * Initializes the interpreter.
     *
     * @throws IllegalStateException if libpython cannot be loaded, or an interpreter
     *     is already running in this process
     */
    public static EmbeddedPython start() {
        LibPython.Selection selection = LibPython.load();
        if (isInitialized()) {
            throw new IllegalStateException("A Python interpreter is already initialized");
        }
        // Present the interpreter as the python executable that libpython was found
        // through. Python derives sys.executable and sys.prefix from it, which is how
        // a virtual environment (found via the pyvenv.cfg beside it) takes effect.
        selection.executable().ifPresent(executable -> {
            // Py_SetProgramName is deprecated since 3.11 in favour of PyConfig, but
            // PyConfig's layout differs between minor versions and is not in the
            // Stable ABI. The string must stay valid "for the duration of the
            // program's execution", hence the global arena.
            Py_SetProgramName(Arena.global().allocateFrom(executable.toString(), WCHAR_CHARSET));
        });
        // initsigs = 0: do not let Python install signal handlers (SIGINT etc.),
        // which belong to the JVM.
        Py_InitializeEx(0);
        return new EmbeddedPython(selection);
    }

    /** Whether a Python interpreter is currently initialized in this process. */
    public static boolean isInitialized() {
        LibPython.load();
        return Py_IsInitialized() != 0;
    }

    /** The libpython shared library this interpreter runs from. */
    public Path library() {
        return selection.library();
    }

    /**
     * The python executable this interpreter presents itself as, which determines
     * {@code sys.executable} and {@code sys.prefix}; empty when libpython was
     * given directly with {@code -Dpython.library}.
     */
    public Optional<Path> executable() {
        return selection.executable();
    }

    /**
     * Reports how the interpreter was configured, as returned by CPython's
     * {@code Py_Get*()} functions, keyed by function name in a stable order. The
     * values are {@code null} where CPython returns {@code NULL} (e.g. an unset
     * {@code Py_GetPythonHome()}).
     *
     * <p>The path functions ({@code Py_GetPrefix()} etc.) are deprecated since 3.13
     * and no longer declared in the 3.15 headers, in favour of {@code sys} and
     * {@code PyConfig_Get()}. Being part of the Stable ABI, they are still exported.
     */
    public SequencedMap<String, String> configuration() {
        checkUsable();
        SequencedMap<String, String> configuration = new LinkedHashMap<>();
        // Path configuration: wchar_t strings owned by CPython.
        put(configuration, "Py_GetProgramName", Py_GetProgramName(), WCHAR_CHARSET);
        put(configuration, "Py_GetProgramFullPath", Py_GetProgramFullPath(), WCHAR_CHARSET);
        put(configuration, "Py_GetPythonHome", Py_GetPythonHome(), WCHAR_CHARSET);
        put(configuration, "Py_GetPrefix", Py_GetPrefix(), WCHAR_CHARSET);
        put(configuration, "Py_GetExecPrefix", Py_GetExecPrefix(), WCHAR_CHARSET);
        put(configuration, "Py_GetPath", Py_GetPath(), WCHAR_CHARSET);
        // Build information: static UTF-8 strings.
        put(configuration, "Py_GetVersion", Py_GetVersion(), StandardCharsets.UTF_8);
        put(configuration, "Py_GetPlatform", Py_GetPlatform(), StandardCharsets.UTF_8);
        put(configuration, "Py_GetCompiler", Py_GetCompiler(), StandardCharsets.UTF_8);
        put(configuration, "Py_GetBuildInfo", Py_GetBuildInfo(), StandardCharsets.UTF_8);
        return configuration;
    }

    private static void put(SequencedMap<String, String> map, String name, MemorySegment string, Charset charset) {
        // The generated bindings give char/wchar_t pointers an unbounded target
        // layout, so the NUL-terminated string can be read directly.
        map.put(name, string.equals(MemorySegment.NULL) ? null : string.getString(0, charset));
    }

    /**
     * Runs Python source code in the {@code __main__} module, like
     * {@code PyRun_SimpleString} (which is not part of the Stable ABI).
     *
     * <p>As with {@code PyRun_SimpleString}, an uncaught {@code SystemExit} exits the
     * whole process, JVM included.
     *
     * @throws PythonException if the code raised an exception; Python has already
     *     printed the traceback to stderr
     */
    public void exec(String source) {
        checkUsable();
        try (Arena arena = Arena.ofConfined()) {
            // Both return borrowed references: nothing to release.
            MemorySegment main = PyImport_AddModule(arena.allocateFrom("__main__"));
            if (main.equals(MemorySegment.NULL)) {
                throw pythonError("could not get the __main__ module");
            }
            MemorySegment globals = PyModule_GetDict(main);

            // allocateFrom copies the Java string into native memory as NUL-terminated
            // UTF-8, which is what CPython expects; the arena frees it afterwards.
            MemorySegment code = Py_CompileString(arena.allocateFrom(source), arena.allocateFrom("<string>"), Py_file_input());
            if (code.equals(MemorySegment.NULL)) {
                throw pythonError("Python code failed to compile");
            }
            MemorySegment result = PyEval_EvalCode(code, globals, globals);
            Py_DecRef(code);
            if (result.equals(MemorySegment.NULL)) {
                throw pythonError("Python code raised an exception");
            }
            Py_DecRef(result);
        }
    }

    /** Prints and clears the pending Python exception, returning a Java one to throw. */
    private static PythonException pythonError(String message) {
        PyErr_Print();
        return new PythonException(message + " (traceback printed to stderr)");
    }

    /**
     * Finalizes the interpreter, flushing Python's buffered output.
     *
     * @throws PythonException if flushing buffered data failed during finalization
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        checkUsable();
        closed = true;
        if (Py_FinalizeEx() < 0) {
            throw new PythonException("Py_FinalizeEx failed to flush buffered data");
        }
    }

    private void checkUsable() {
        if (closed) {
            throw new IllegalStateException("The Python interpreter has been finalized");
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "The Python interpreter must be used from the thread that started it: " + owner);
        }
    }
}
