package com.example.ffm.python;

import java.util.Objects;

/**
 * How {@link EmbeddedPython#newSubInterpreterFromConfig(InterpreterConfig)} creates a
 * sub-interpreter: CPython's {@code PyInterpreterConfig} (3.12+), field for field.
 *
 * <p>Start from {@link #isolated()} or {@link #legacy()} and adjust with the
 * {@code with*} methods. The constructor rejects combinations CPython does not
 * support, before CPython sees them; see {@link #InterpreterConfig}.
 *
 * @param useMainObmalloc share the main interpreter's object allocator, rather
 *     than having its own
 * @param allowFork allow {@code os.fork()}
 * @param allowExec allow {@code os.exec*()}
 * @param allowThreads allow the {@code threading} module to start threads
 * @param allowDaemonThreads allow daemon threads
 * @param checkMultiInterpExtensions only import extension modules that support
 *     multiple interpreters (multi-phase initialization)
 * @param gil whether to share the main interpreter's GIL or have its own
 */
public record InterpreterConfig(
        boolean useMainObmalloc,
        boolean allowFork,
        boolean allowExec,
        boolean allowThreads,
        boolean allowDaemonThreads,
        boolean checkMultiInterpExtensions,
        Gil gil) {

    /**
     * {@code PyInterpreterConfig.gil}. CPython's third value,
     * {@code PyInterpreterConfig_DEFAULT_GIL}, means {@link #SHARED}.
     */
    public enum Gil {
        /** Share the main interpreter's GIL: no parallelism with it. */
        SHARED,
        /** A GIL of its own: runs in parallel with other interpreters. */
        OWN
    }

    /**
     * Validates the combination.
     *
     * <p>Up front rather than leaving it to CPython: CPython 3.12's error path for a
     * rejected configuration returns with the calling thread's GIL silently
     * released, so the next GIL operation aborts the process.
     *
     * @throws IllegalArgumentException if CPython does not support the combination
     */
    public InterpreterConfig {
        Objects.requireNonNull(gil, "gil");
        // Enforced by CPython ("per-interpreter obmalloc does not support
        // single-phase init extension modules").
        if (!useMainObmalloc && !checkMultiInterpExtensions) {
            throw new IllegalArgumentException(
                    "An interpreter with its own obmalloc must only import multi-phase-init extension modules"
                            + " (checkMultiInterpExtensions)");
        }
        // Documented, but not enforced by CPython 3.13+: two GILs sharing one
        // allocator would race.
        if (useMainObmalloc && gil == Gil.OWN) {
            throw new IllegalArgumentException(
                    "An interpreter with its own GIL must have its own obmalloc (useMainObmalloc = false)");
        }
    }

    /**
     * CPython's {@code _PyInterpreterConfig_INIT}: fully isolated, with its own GIL
     * and allocator. Runs in parallel with other interpreters, but cannot fork, exec,
     * start daemon threads or import single-phase-init extension modules.
     */
    public static InterpreterConfig isolated() {
        return new InterpreterConfig(false, false, false, true, false, true, Gil.OWN);
    }

    /**
     * CPython's {@code _PyInterpreterConfig_LEGACY_INIT}: what the Stable ABI's
     * {@code Py_NewInterpreter()} uses. Shares the main interpreter's GIL and
     * allocator, and allows everything.
     */
    public static InterpreterConfig legacy() {
        return new InterpreterConfig(true, true, true, true, true, false, Gil.SHARED);
    }

    public InterpreterConfig withUseMainObmalloc(boolean value) {
        return new InterpreterConfig(value, allowFork, allowExec, allowThreads, allowDaemonThreads, checkMultiInterpExtensions, gil);
    }

    public InterpreterConfig withAllowFork(boolean value) {
        return new InterpreterConfig(useMainObmalloc, value, allowExec, allowThreads, allowDaemonThreads, checkMultiInterpExtensions, gil);
    }

    public InterpreterConfig withAllowExec(boolean value) {
        return new InterpreterConfig(useMainObmalloc, allowFork, value, allowThreads, allowDaemonThreads, checkMultiInterpExtensions, gil);
    }

    public InterpreterConfig withAllowThreads(boolean value) {
        return new InterpreterConfig(useMainObmalloc, allowFork, allowExec, value, allowDaemonThreads, checkMultiInterpExtensions, gil);
    }

    public InterpreterConfig withAllowDaemonThreads(boolean value) {
        return new InterpreterConfig(useMainObmalloc, allowFork, allowExec, allowThreads, value, checkMultiInterpExtensions, gil);
    }

    public InterpreterConfig withCheckMultiInterpExtensions(boolean value) {
        return new InterpreterConfig(useMainObmalloc, allowFork, allowExec, allowThreads, allowDaemonThreads, value, gil);
    }

    public InterpreterConfig withGil(Gil value) {
        return new InterpreterConfig(useMainObmalloc, allowFork, allowExec, allowThreads, allowDaemonThreads, checkMultiInterpExtensions, value);
    }
}
