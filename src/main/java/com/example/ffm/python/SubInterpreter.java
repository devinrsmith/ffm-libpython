package com.example.ffm.python;

import static com.example.ffm.python.capi.stable.CApi.PyEval_RestoreThread;
import static com.example.ffm.python.capi.stable.CApi.PyEval_SaveThread;
import static com.example.ffm.python.capi.stable.CApi.Py_EndInterpreter;

import java.lang.foreign.MemorySegment;

/**
 * A CPython sub-interpreter, created by {@link EmbeddedPython#newSubInterpreter()} or
 * {@link EmbeddedPython#newSubInterpreterFromConfig} and valid until {@link #close()}.
 *
 * <p>It has its own modules, {@code sys} and {@code __main__}, separate from the main
 * interpreter's and from other sub-interpreters'. Whether it runs Python code in
 * parallel with interpreters on other threads depends on its {@link #config()}: only
 * with a GIL of its own.
 *
 * <p>It is confined to the platform thread that created it: CPython ties the
 * sub-interpreter's thread state to that OS thread.
 */
public final class SubInterpreter implements AutoCloseable {

    private final EmbeddedPython parent;
    private final Thread owner;
    /** This interpreter's thread state, detached (its GIL released) between calls. */
    private final MemorySegment threadState;
    private final InterpreterConfig config;
    private boolean closed;

    SubInterpreter(EmbeddedPython parent, MemorySegment threadState, InterpreterConfig config) {
        this.parent = parent;
        this.owner = Thread.currentThread();
        this.threadState = threadState;
        this.config = config;
    }

    /**
     * How it was created: {@link InterpreterConfig#legacy()} for
     * {@link EmbeddedPython#newSubInterpreter()}, which is what
     * {@code Py_NewInterpreter()} uses.
     */
    public InterpreterConfig config() {
        return config;
    }

    /** Diagnostics about this sub-interpreter, from {@code PyInterpreterState_Get*()}. */
    public InterpreterInfo interpreterInfo() {
        checkUsable();
        PyEval_RestoreThread(threadState);
        try {
            return InterpreterInfo.current();
        } finally {
            PyEval_SaveThread();
        }
    }

    /**
     * Runs Python source code in this sub-interpreter's {@code __main__} module.
     *
     * @throws PythonException if the code raised an exception; Python has already
     *     printed the traceback to stderr
     * @see EmbeddedPython#exec(String)
     */
    public void exec(String source) {
        checkUsable();
        // Takes this interpreter's GIL: its own, or the main one if shared.
        PyEval_RestoreThread(threadState);
        try {
            PythonCode.run(source);
        } finally {
            PyEval_SaveThread();
        }
    }

    /** Ends the sub-interpreter, releasing its modules (and its GIL, if its own). */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        checkUsable();
        closed = true;
        PyEval_RestoreThread(threadState);
        // Requires its thread state to be current; leaves no current thread state.
        Py_EndInterpreter(threadState);
        parent.ended(this);
    }

    private void checkUsable() {
        if (closed) {
            throw new IllegalStateException("The sub-interpreter has been ended");
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "A sub-interpreter must be used from the thread that created it: " + owner);
        }
    }

    static void checkPlatformThread() {
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalStateException(
                    "Python thread states are tied to OS threads; use a platform thread, not a virtual one");
        }
    }
}
