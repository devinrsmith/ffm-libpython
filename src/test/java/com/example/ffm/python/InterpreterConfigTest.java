package com.example.ffm.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ffm.python.InterpreterConfig.Gil;
import org.junit.jupiter.api.Test;

class InterpreterConfigTest {

    @Test
    void presetsMatchCPython() {
        // _PyInterpreterConfig_INIT
        assertEquals(new InterpreterConfig(false, false, false, true, false, true, Gil.OWN), InterpreterConfig.isolated());
        // _PyInterpreterConfig_LEGACY_INIT
        assertEquals(new InterpreterConfig(true, true, true, true, true, false, Gil.SHARED), InterpreterConfig.legacy());
    }

    @Test
    void ownObmallocRequiresMultiPhaseExtensions() {
        // CPython rejects this itself, but on 3.12 doing so leaves the GIL released.
        assertThrows(IllegalArgumentException.class,
                () -> InterpreterConfig.isolated().withCheckMultiInterpExtensions(false));
    }

    @Test
    void ownGilRequiresOwnObmalloc() {
        // Documented by CPython, but not enforced by it on 3.13+.
        assertThrows(IllegalArgumentException.class, () -> InterpreterConfig.isolated().withUseMainObmalloc(true));
        assertThrows(IllegalArgumentException.class, () -> InterpreterConfig.legacy().withGil(Gil.OWN));
    }

    @Test
    void validCombinationsAreAccepted() {
        // Own obmalloc with a shared GIL is fine.
        InterpreterConfig config = InterpreterConfig.isolated().withGil(Gil.SHARED);
        assertFalse(config.useMainObmalloc());
        // Order matters: share the GIL before sharing the allocator.
        assertTrue(config.withUseMainObmalloc(true).useMainObmalloc());
        // The permissions are independent of everything else.
        assertTrue(InterpreterConfig.isolated().withAllowFork(true).withAllowExec(true).withAllowDaemonThreads(true).allowFork());
    }
}
