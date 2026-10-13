package com.example.ffm.python;

import static com.example.ffm.python.capi.stable.CApi.Py_FinalizeEx;
import static com.example.ffm.python.capi.stable.CApi.Py_GetBuildInfo;
import static com.example.ffm.python.capi.stable.CApi.Py_GetCompiler;
import static com.example.ffm.python.capi.stable.CApi.Py_GetExecPrefix;
import static com.example.ffm.python.capi.stable.CApi.Py_GetPath;
import static com.example.ffm.python.capi.stable.CApi.Py_GetPlatform;
import static com.example.ffm.python.capi.stable.CApi.Py_GetPrefix;
import static com.example.ffm.python.capi.stable.CApi.Py_GetProgramFullPath;
import static com.example.ffm.python.capi.stable.CApi.Py_GetProgramName;
import static com.example.ffm.python.capi.stable.CApi.Py_GetPythonHome;
import static com.example.ffm.python.capi.stable.CApi.Py_GetVersion;
import static com.example.ffm.python.capi.stable.CApi.Py_InitializeEx;
import static com.example.ffm.python.capi.stable.CApi.Py_IsInitialized;
import static com.example.ffm.python.capi.stable.CApi.Py_SetProgramName;

import com.example.ffm.python.internal.GilState;
import com.example.ffm.python.internal.Py;
import com.example.ffm.python.internal.Py312;
import com.example.ffm.python.internal.PyThreadState;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An embedded CPython interpreter, valid from {@link #start()} until {@link #close()}.
 *
 * <p>This is CPython's main interpreter, of which there is one per process. It is
 * confined to the thread that started it, which attaches to it (taking the main
 * GIL) only for the duration of each call; in between, the GIL is released. Other
 * threads can create {@linkplain #newSubInterpreter() sub-interpreters}, which
 * share its GIL, or {@linkplain #newSubInterpreterFromConfig configured} ones, with
 * their own GIL.
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
    /** The owner thread's thread state, detached (GIL released) between calls. */
    private final PyThreadState threadState;
    /** Guards closed and creating sub-interpreters against finalization. */
    private final Object lifecycle = new Object();
    private final Set<SubInterpreter> subInterpreters = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private EmbeddedPython(LibPython.Selection selection, PyThreadState threadState) {
        this.selection = selection;
        this.owner = Thread.currentThread();
        this.threadState = threadState;
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
        // Release the GIL, so that other threads can attach (e.g. to create
        // sub-interpreters) while this one is not running Python code.
        return new EmbeddedPython(selection, Py.evalSaveThread());
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
     * The version of the embedded libpython, unpacked from its runtime constant
     * {@code Py_Version} (which, unlike the {@code PY_VERSION_HEX} macro, describes
     * the library actually loaded rather than the headers compiled against).
     */
    public PythonVersion version() {
        return LibPython.version();
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
        Py.evalRestoreThread(threadState);
        try {
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
        } finally {
            Py.evalSaveThread();
        }
        return configuration;
    }

    private static void put(SequencedMap<String, String> map, String name, MemorySegment string, Charset charset) {
        // The generated bindings give char/wchar_t pointers an unbounded target
        // layout, so the NUL-terminated string can be read directly.
        map.put(name, string.equals(MemorySegment.NULL) ? null : string.getString(0, charset));
    }

    /** Diagnostics about the main interpreter, from {@code PyInterpreterState_Get*()}. */
    public InterpreterInfo interpreterInfo() {
        checkUsable();
        Py.evalRestoreThread(threadState);
        try {
            return InterpreterInfo.current();
        } finally {
            Py.evalSaveThread();
        }
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
        Py.evalRestoreThread(threadState);
        try {
            PythonCode.run(source);
        } finally {
            Py.evalSaveThread();
        }
    }

    /**
     * Creates a sub-interpreter with the Stable ABI's {@code Py_NewInterpreter()},
     * confined to the calling thread.
     *
     * <p>It has its own modules, {@code sys} and {@code __main__}, but shares the
     * main interpreter's GIL ({@link InterpreterConfig#legacy()}): sub-interpreters
     * created this way take turns with each other and with the main interpreter
     * rather than running in parallel. In exchange it allows everything, including
     * single-phase-init extension modules, fork and exec. For parallelism, use
     * {@link #newSubInterpreterFromConfig} with {@link InterpreterConfig#isolated()}.
     *
     * <p>Can be called from any platform thread (not a virtual thread: CPython ties
     * a thread state to its OS thread). Every sub-interpreter must be closed before
     * this interpreter is.
     *
     * @throws PythonException if CPython fails to create it
     */
    public SubInterpreter newSubInterpreter() {
        return newSubInterpreter(InterpreterConfig.legacy(), Py::newInterpreter);
    }

    /**
     * Creates a sub-interpreter with an explicit configuration, via CPython's
     * version-specific {@code Py_NewInterpreterFromConfig()} (bound from the 3.12
     * headers, in {@code capi.py312}), confined to the calling thread.
     *
     * <p>With {@link InterpreterConfig#isolated()} it has its own GIL, so
     * sub-interpreters on different threads run Python code in parallel, with each
     * other and with the main interpreter. Isolation has a cost: extension modules
     * using legacy single-phase initialization cannot be imported, and it cannot
     * fork, exec or start daemon threads.
     *
     * <p>Thread rules as for {@link #newSubInterpreter()}.
     *
     * @throws PythonException if CPython fails to create it
     */
    public SubInterpreter newSubInterpreterFromConfig(InterpreterConfig config) {
        Objects.requireNonNull(config, "config");
        // Already validated by InterpreterConfig: CPython 3.12 must never get to
        // reject a configuration (its error path loses the GIL).
        return newSubInterpreter(config, () -> Py312.newInterpreterFromConfig(config));
    }

    /**
     * Attaches the calling thread to the main interpreter, creates a sub-interpreter
     * with {@code create}, and detaches again.
     *
     * @param create calls into CPython to create the sub-interpreter, returning its
     *     thread state, which CPython has made current
     */
    private SubInterpreter newSubInterpreter(InterpreterConfig config, Supplier<PyThreadState> create) {
        SubInterpreter.checkPlatformThread();
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("The Python interpreter has been finalized");
            }
            // Creating a sub-interpreter requires a current thread state. Reuses the
            // owner's thread state when called from the owner thread.
            GilState gilState = Py.gilStateEnsure();
            try {
                PyThreadState mainState = Py.threadStateGet();
                // On failure, CPython restores mainState as the current thread state.
                PyThreadState subState = create.get();
                // Both C functions document that the new thread state becomes the
                // current one, holding its GIL (its own, in which case the main GIL
                // was released; or the shared one). Check that before relying on it:
                // detaching, and later ending, the wrong thread state would corrupt
                // CPython.
                PyThreadState current = Py.threadStateGet();
                if (subState.equals(mainState) || !subState.equals(current)) {
                    throw new IllegalStateException(String.format(
                            "Sub-interpreter creation returned thread state 0x%x, but 0x%x is current"
                                    + " (main: 0x%x); CPython's state is now undefined",
                            subState.segment().address(), current.segment().address(), mainState.segment().address()));
                }
                // Detach it, then return to the main interpreter so that
                // PyGILState_Release can undo PyGILState_Ensure.
                Py.evalSaveThread();
                Py.evalRestoreThread(mainState);
                SubInterpreter subInterpreter = new SubInterpreter(this, subState, config);
                subInterpreters.add(subInterpreter);
                return subInterpreter;
            } finally {
                Py.gilStateRelease(gilState);
            }
        }
    }

    /** Called by a sub-interpreter once it has ended. */
    void ended(SubInterpreter subInterpreter) {
        subInterpreters.remove(subInterpreter);
    }

    /**
     * Finalizes the interpreter, flushing Python's buffered output.
     *
     * @throws IllegalStateException if sub-interpreters are still open
     * @throws PythonException if flushing buffered data failed during finalization
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            checkUsable();
            if (!subInterpreters.isEmpty()) {
                throw new IllegalStateException(subInterpreters.size() + " sub-interpreter(s) still open");
            }
            closed = true;
            Py.evalRestoreThread(threadState);
            if (Py_FinalizeEx() < 0) {
                throw new PythonException("Py_FinalizeEx failed to flush buffered data");
            }
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
