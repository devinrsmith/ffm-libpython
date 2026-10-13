package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
            // The Py_Get*() diagnostics (deprecated, and gone from the 3.15 headers, but
            // still exported). Py_GetPrefix() is not checked: up to 3.13 it reports the
            // venv's base installation, from 3.14 on the venv itself.
            Map<String, String> configuration = python.configuration();
            String venvPython = Path.of(venv, "bin", "python").toString();
            assertEquals(venvPython, configuration.get("Py_GetProgramName"));
            assertEquals(venvPython, configuration.get("Py_GetProgramFullPath"));
            assertTrue(configuration.get("Py_GetVersion").startsWith(LibPython.format(LibPython.version()) + " "),
                    configuration.get("Py_GetVersion"));
            assertNotNull(configuration.get("Py_GetPlatform"));
            assertDoesNotThrow(() -> python.exec("assert 6 * 7 == 42"));
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

    /** A Python string literal for {@code value}. */
    private static String pythonString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
