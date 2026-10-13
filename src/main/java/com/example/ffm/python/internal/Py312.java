package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.py312.CPython312.PyInterpreterConfig_OWN_GIL;
import static com.example.ffm.python.capi.py312.CPython312.PyInterpreterConfig_SHARED_GIL;
import static com.example.ffm.python.capi.py312.CPython312.PyInterpreterState_Main;
import static com.example.ffm.python.capi.py312.CPython312.PyStatus_Exception;
import static com.example.ffm.python.capi.py312.CPython312.Py_NewInterpreterFromConfig;
import static java.lang.foreign.ValueLayout.ADDRESS;

import com.example.ffm.python.InterpreterConfig;
import com.example.ffm.python.PythonException;
import com.example.ffm.python.capi.py312.PyInterpreterConfig;
import com.example.ffm.python.capi.py312.PyStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Typed wrappers for CPython's version-specific API, as bound from the 3.12 headers
 * ({@code capi.py312.CPython312}); same conventions as {@link Py}.
 */
public final class Py312 {

    private Py312() {}

    /** {@code PyInterpreterState_Main}: the main interpreter. */
    public static PyInterpreterState interpreterStateMain() {
        return new PyInterpreterState(PyInterpreterState_Main());
    }

    /**
     * {@code Py_NewInterpreterFromConfig}: a sub-interpreter configured by
     * {@code config}. Returns its thread state, which is now current.
     *
     * <p>{@code config} must already be valid ({@link InterpreterConfig} validates
     * itself): CPython 3.12's error path for a rejected configuration leaves the GIL
     * released.
     *
     * @throws PythonException if CPython fails to create it
     */
    public static PyThreadState newInterpreterFromConfig(InterpreterConfig config) {
        try (Arena arena = Arena.ofConfined()) {
            // Plain C structs in our arena: not refcounted, and not retained by CPython.
            MemorySegment nativeConfig = PyInterpreterConfig.allocate(arena);
            PyInterpreterConfig.use_main_obmalloc(nativeConfig, config.useMainObmalloc() ? 1 : 0);
            PyInterpreterConfig.allow_fork(nativeConfig, config.allowFork() ? 1 : 0);
            PyInterpreterConfig.allow_exec(nativeConfig, config.allowExec() ? 1 : 0);
            PyInterpreterConfig.allow_threads(nativeConfig, config.allowThreads() ? 1 : 0);
            PyInterpreterConfig.allow_daemon_threads(nativeConfig, config.allowDaemonThreads() ? 1 : 0);
            PyInterpreterConfig.check_multi_interp_extensions(nativeConfig, config.checkMultiInterpExtensions() ? 1 : 0);
            PyInterpreterConfig.gil(nativeConfig, switch (config.gil()) {
                case SHARED -> PyInterpreterConfig_SHARED_GIL();
                case OWN -> PyInterpreterConfig_OWN_GIL();
            });

            MemorySegment newState = arena.allocate(ADDRESS);
            MemorySegment status = Py_NewInterpreterFromConfig(arena, newState, nativeConfig);
            if (PyStatus_Exception(status) != 0) {
                // A static C string owned by CPython.
                MemorySegment message = PyStatus.err_msg(status);
                throw new PythonException("Py_NewInterpreterFromConfig failed: "
                        + (message.equals(MemorySegment.NULL) ? "unknown error" : message.getString(0)));
            }
            return new PyThreadState(newState.get(ADDRESS, 0));
        }
    }
}
