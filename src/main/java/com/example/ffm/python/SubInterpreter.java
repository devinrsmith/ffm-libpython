package com.example.ffm.python;

import static com.example.ffm.python.capi.stable.CApi.PyEval_RestoreThread;
import static com.example.ffm.python.capi.stable.CApi.PyEval_SaveThread;
import static com.example.ffm.python.capi.stable.CApi.Py_EndInterpreter;

import java.lang.foreign.MemorySegment;

/**
 * An isolated CPython sub-interpreter with its own GIL, created by
 * {@link EmbeddedPython#newSubInterpreter()} and valid until {@link #close()}.
 *
 * <p>It has its own modules, {@code sys} and {@code __main__}, separate from the main
 * interpreter's and from other sub-interpreters'. Because it has its own GIL, it
 * runs Python code in parallel with interpreters on other threads.
 *
 * <p>It is confined to the platform thread that created it: CPython ties the
 * sub-interpreter's thread state to that OS thread.
 */
public final class SubInterpreter implements AutoCloseable {

    private final EmbeddedPython parent;
    private final Thread owner;
    /** This interpreter's thread state, detached (its GIL released) between calls. */
    private final MemorySegment threadState;
    private boolean closed;

    SubInterpreter(EmbeddedPython parent, MemorySegment threadState) {
        this.parent = parent;
        this.owner = Thread.currentThread();
        this.threadState = threadState;
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
        // Attaching takes only this interpreter's own GIL, never the main one.
        PyEval_RestoreThread(threadState);
        try {
            PythonCode.run(source);
        } finally {
            PyEval_SaveThread();
        }
    }

    /** Ends the sub-interpreter, releasing its modules and its GIL. */
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
