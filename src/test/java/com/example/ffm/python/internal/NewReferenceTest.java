package com.example.ffm.python.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewReferenceTest {

    private final PyObject.Any object = new PyObject.Any(MemorySegment.ofAddress(0x1000));
    private final List<PyObject> released = new ArrayList<>();

    private NewReference<PyObject.Any> reference() {
        return new NewReference<>(object, released::add);
    }

    @Test
    void releasesExactlyOnce() {
        NewReference<PyObject.Any> reference = reference();
        assertSame(object, reference.get());
        reference.close();
        reference.close();
        assertEquals(List.of(object), released);
    }

    @Test
    void cannotBeUsedOnceReleased() {
        NewReference<PyObject.Any> reference = reference();
        reference.close();
        assertThrows(IllegalStateException.class, reference::get);
    }

    @Test
    void releasesWhenTheBodyThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            try (NewReference<PyObject.Any> reference = reference()) {
                reference.get();
                throw new IllegalArgumentException("boom");
            }
        });
        assertEquals(List.of(object), released);
    }
}
