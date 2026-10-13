package com.example.ffm.python;

import static com.example.ffm.python.capi.CApi.PyErr_Print;
import static com.example.ffm.python.capi.CApi.PyEval_EvalCode;
import static com.example.ffm.python.capi.CApi.PyImport_AddModule;
import static com.example.ffm.python.capi.CApi.PyModule_GetDict;
import static com.example.ffm.python.capi.CApi.Py_CompileString;
import static com.example.ffm.python.capi.CApi.Py_DecRef;
import static com.example.ffm.python.capi.CApi.Py_FinalizeEx;
import static com.example.ffm.python.capi.CApi.Py_InitializeEx;
import static com.example.ffm.python.capi.CApi.Py_IsInitialized;
import static com.example.ffm.python.capi.CApi.Py_file_input;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

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

    private final Path library;
    private final Thread owner;
    private boolean closed;

    private EmbeddedPython(Path library) {
        this.library = library;
        this.owner = Thread.currentThread();
    }

    /**
     * Initializes the interpreter.
     *
     * @throws IllegalStateException if libpython cannot be loaded, or an interpreter
     *     is already running in this process
     */
    public static EmbeddedPython start() {
        Path library = LibPython.load();
        if (isInitialized()) {
            throw new IllegalStateException("A Python interpreter is already initialized");
        }
        // initsigs = 0: do not let Python install signal handlers (SIGINT etc.),
        // which belong to the JVM.
        Py_InitializeEx(0);
        return new EmbeddedPython(library);
    }

    /** Whether a Python interpreter is currently initialized in this process. */
    public static boolean isInitialized() {
        LibPython.load();
        return Py_IsInitialized() != 0;
    }

    /** The libpython shared library this interpreter runs from. */
    public Path library() {
        return library;
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
