package com.example.ffm.python.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class PyObjectTest {

    @Test
    void wrappersRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> new PyObject.Dict(MemorySegment.NULL));
        assertThrows(IllegalArgumentException.class, () -> new PyObject.Module(null));
        assertThrows(IllegalArgumentException.class, () -> new PyThreadState(MemorySegment.NULL));
        assertThrows(IllegalArgumentException.class, () -> new PyInterpreterState(MemorySegment.NULL));
    }

    @Test
    void equalityIsByAddress() {
        // Pointers from different bindings carry different sizes; equality ignores that.
        MemorySegment unsized = MemorySegment.ofAddress(0x1000);
        MemorySegment sized = unsized.reinterpret(Long.MAX_VALUE);
        assertEquals(new PyThreadState(unsized), new PyThreadState(sized));
    }
}
