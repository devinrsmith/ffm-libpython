package com.example.ffm.python;

import static com.example.ffm.python.capi.CApi.PyRun_SimpleStringFlags;
import static com.example.ffm.python.capi.CApi.Py_FinalizeEx;
import static com.example.ffm.python.capi.CApi.Py_InitializeEx;
import static com.example.ffm.python.capi.CApi.Py_IsInitialized;
import static com.example.ffm.python.capi.CApi.RTLD_GLOBAL;
import static com.example.ffm.python.capi.CApi.RTLD_NOLOAD;
import static com.example.ffm.python.capi.CApi.RTLD_NOW;
import static com.example.ffm.python.capi.CApi.dladdr;
import static com.example.ffm.python.capi.CApi.dlerror;
import static com.example.ffm.python.capi.CApi.dlopen;

import com.example.ffm.python.capi.Dl_info;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;

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
 */
public final class EmbeddedPython implements AutoCloseable {

    private final Thread owner;
    private boolean closed;

    private EmbeddedPython() {
        this.owner = Thread.currentThread();
    }

    /**
     * Initializes the interpreter.
     *
     * @throws IllegalStateException if an interpreter is already running in this process
     */
    public static EmbeddedPython start() {
        if (isInitialized()) {
            throw new IllegalStateException("A Python interpreter is already initialized");
        }
        exportLibPythonSymbols();
        // initsigs = 0: do not let Python install signal handlers (SIGINT etc.),
        // which belong to the JVM.
        Py_InitializeEx(0);
        return new EmbeddedPython();
    }

    /** Whether a Python interpreter is currently initialized in this process. */
    public static boolean isInitialized() {
        return Py_IsInitialized() != 0;
    }

    /**
     * Runs Python source code in the {@code __main__} module.
     *
     * @throws PythonException if the code raised an exception; Python has already
     *     printed the traceback to stderr
     */
    public void exec(String source) {
        checkUsable();
        try (Arena arena = Arena.ofConfined()) {
            // Copies the string into native memory as NUL-terminated UTF-8, which
            // is what CPython expects; the arena frees it when the call returns.
            MemorySegment cSource = arena.allocateFrom(source);
            if (PyRun_SimpleStringFlags(cSource, MemorySegment.NULL) != 0) {
                throw new PythonException("Python code raised an exception (traceback printed to stderr)");
            }
        }
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

    /**
     * Makes libpython's symbols globally visible.
     *
     * <p>{@code System.loadLibrary} (used by the generated bindings) opens
     * libpython with {@code RTLD_LOCAL}. C extension modules such as
     * {@code math} expect to resolve symbols like {@code PyExc_StopIteration}
     * from the process's global namespace, so without this even
     * {@code import math} fails with "undefined symbol". Re-opening the
     * already-loaded library with {@code RTLD_GLOBAL} fixes that.
     */
    private static void exportLibPythonSymbols() {
        try (Arena arena = Arena.ofConfined()) {
            // Ask the dynamic linker which file the loaded libpython came from.
            MemorySegment symbol = SymbolLookup.loaderLookup().findOrThrow("Py_InitializeEx");
            MemorySegment info = Dl_info.allocate(arena);
            if (dladdr(symbol, info) == 0) {
                throw new IllegalStateException("dladdr could not locate libpython");
            }
            MemorySegment path = Dl_info.dli_fname(info);
            // RTLD_NOLOAD: only promote the existing mapping, never load a second copy.
            if (dlopen(path, RTLD_NOW() | RTLD_NOLOAD() | RTLD_GLOBAL()).equals(MemorySegment.NULL)) {
                throw new IllegalStateException(
                        "dlopen(" + path.getString(0) + ") failed: " + dlerror().getString(0));
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
