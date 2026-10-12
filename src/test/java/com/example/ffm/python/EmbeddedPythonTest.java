package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
