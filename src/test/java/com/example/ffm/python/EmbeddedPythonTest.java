package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbeddedPythonTest {

    // CPython cannot be reliably re-initialized after finalization within one
    // process, so the whole lifecycle is exercised in a single test.
    @Test
    void lifecycle() {
        assertFalse(EmbeddedPython.isInitialized());

        EmbeddedPython python = EmbeddedPython.start();
        try {
            assertTrue(EmbeddedPython.isInitialized());
            assertThrows(IllegalStateException.class, EmbeddedPython::start);

            assertDoesNotThrow(() -> python.exec("print('hello from a test')"));
            // The build points python.executable at a venv: it must be the interpreter's prefix.
            String venv = System.getProperty("test.venv");
            assertDoesNotThrow(() -> python.exec("""
                    import os, sys
                    assert sys.prefix != sys.base_prefix, (sys.prefix, sys.base_prefix)
                    assert os.path.samefile(sys.prefix, %s), sys.prefix
                    assert any(p.startswith(sys.prefix) and p.endswith("site-packages") for p in sys.path), sys.path
                    """.formatted(pythonString(venv))));
            // The configuration: from the Py_Get*() functions up to 3.13, from
            // PyConfig_Get from 3.14 (the former are deprecated, and gone from the
            // 3.15 headers).
            Map<String, String> configuration = python.configuration();
            assertEquals(List.of("program_name", "executable", "home", "prefix", "exec_prefix",
                            "module_search_paths", "version", "platform", "compiler", "build_info"),
                    List.copyOf(configuration.keySet()));
            String venvPython = Path.of(venv, "bin", "python").toString();
            assertEquals(venvPython, configuration.get("program_name"));
            assertEquals(venvPython, configuration.get("executable"));
            // The two documented differences between the sources, in this venv:
            boolean pyConfigGet = python.version().minor() >= 14;
            assertDoesNotThrow(() -> python.exec("""
                    import sys
                    prefix, paths, from_pyconfig_get = %s, %s.split(":"), %s
                    # prefix: Py_GetPrefix() is the base installation; PyConfig_Get, the venv.
                    assert prefix == (sys.prefix if from_pyconfig_get else sys.base_prefix), prefix
                    # module_search_paths: Py_GetPath() is computed before site runs;
                    # PyConfig_Get is the live sys.path, with the venv's site-packages.
                    venv_site = any(p.startswith(sys.prefix) and p.endswith("site-packages") for p in paths)
                    assert venv_site == from_pyconfig_get, paths
                    """.formatted(pythonString(configuration.get("prefix")),
                            pythonString(configuration.get("module_search_paths")),
                            pyConfigGet ? "True" : "False")));
            // version begins with the same version Py_Version encodes.
            assertTrue(configuration.get("version").startsWith(python.version() + " "), configuration.get("version"));
            assertTrue(python.version().compareTo(PythonVersion.of(3, 12, 0)) >= 0, python.version()::toString);
            assertNotNull(configuration.get("platform"));
            assertDoesNotThrow(() -> python.exec("assert 6 * 7 == 42"));
            // exec holds a strong reference to __main__ while the code runs, so
            // removing it from sys.modules does not free it mid-run...
            assertDoesNotThrow(() -> python.exec(MAIN_SURVIVES_ITS_REMOVAL));
            // ...and the next exec gets a fresh __main__.
            assertDoesNotThrow(() -> python.exec("assert 'module' not in globals()"));
            // Every Py_IncRef of __main__ is paired with a Py_DecRef: its reference
            // count is the same after 100 more execs.
            python.exec("import sys; refcount_before = sys.getrefcount(sys.modules['__main__'])");
            for (int i = 0; i < 100; i++) {
                python.exec("pass");
            }
            assertDoesNotThrow(() -> python.exec("""
                    refcount_after = sys.getrefcount(sys.modules['__main__'])
                    assert refcount_after == refcount_before, (refcount_before, refcount_after)
                    """));
            // C extension modules need libpython's symbols to be globally visible.
            assertDoesNotThrow(() -> python.exec("import math, json, ctypes, sqlite3"));
            assertThrows(PythonException.class, () -> python.exec("raise ValueError('expected')"));
            assertThrows(PythonException.class, () -> python.exec("def broken(:"));
        } finally {
            python.close();
        }

        assertFalse(EmbeddedPython.isInitialized());
        assertThrows(IllegalStateException.class, () -> python.exec("pass"));
    }

    /** Removes __main__ from sys.modules, then checks it was not freed. */
    static final String MAIN_SURVIVES_ITS_REMOVAL = """
            import gc, sys, weakref
            module = weakref.ref(sys.modules["__main__"])
            del sys.modules["__main__"]
            gc.collect()
            assert module() is not None, "__main__ was freed while its code was running"
            """;

    /** A Python string literal for {@code value}. */
    static String pythonString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
