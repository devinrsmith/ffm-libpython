package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable312.Stable312.Py_Version;

import com.example.ffm.python.PythonVersion;

/**
 * The CPython API level of the loaded libpython, for typed wrappers that choose
 * between bindings at runtime: e.g. a function that the Stable ABI only gained in
 * 3.13 ({@code capi.stable313}), with a 3.12 fallback. Calling a binding for a
 * function the loaded libpython lacks fails, so such calls must be guarded with
 * {@link #atLeast}.
 */
final class ApiLevel {

    private ApiLevel() {}

    /** Read once, on first use: by then libpython is loaded and checked to be >= 3.12. */
    private static final class Loaded {
        static final PythonVersion VERSION = PythonVersion.fromHex(Py_Version());
    }

    /** The loaded libpython's version, from its {@code Py_Version}. */
    static PythonVersion version() {
        return Loaded.VERSION;
    }

    /**
     * Whether the loaded libpython is at least {@code major.minor}. Compares only
     * major and minor, so pre-releases count: a 3.13 alpha has the 3.13 API.
     */
    static boolean atLeast(int major, int minor) {
        PythonVersion version = version();
        return version.major() > major || (version.major() == major && version.minor() >= minor);
    }
}
