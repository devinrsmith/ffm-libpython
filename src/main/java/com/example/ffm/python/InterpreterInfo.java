package com.example.ffm.python;

import static com.example.ffm.python.capi.py312.CPython312.PyInterpreterState_Main;
import static com.example.ffm.python.capi.stable.CApi.PyDict_Size;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_Get;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_GetDict;
import static com.example.ffm.python.capi.stable.CApi.PyInterpreterState_GetID;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_Get;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_GetID;
import static com.example.ffm.python.capi.stable.CApi.PyThreadState_GetInterpreter;

import java.lang.foreign.MemorySegment;
import java.util.OptionalLong;

/**
 * Diagnostics about one CPython interpreter (main or sub-interpreter), from the
 * {@code PyInterpreterState_Get*()} family.
 *
 * @param id {@code PyInterpreterState_GetID()}: unique within the process, never
 *     reused; the main interpreter's is {@code 0}
 * @param main whether this is {@code PyInterpreterState_Main()}
 * @param stateAddress the address of the {@code PyInterpreterState}, from
 *     {@code PyInterpreterState_Get()}
 * @param threadStateId {@code PyThreadState_GetID()} of the thread state used to
 *     reach the interpreter, i.e. the one owned by the {@code EmbeddedPython} or
 *     {@code SubInterpreter}. Numbered per interpreter, so typically {@code 1}
 * @param dictSize the number of entries in {@code PyInterpreterState_GetDict()},
 *     where extensions keep per-interpreter data; empty if there is no such dict
 */
public record InterpreterInfo(long id, boolean main, long stateAddress, long threadStateId, OptionalLong dictSize) {

    /** Reads the diagnostics of the interpreter the calling thread is attached to. */
    static InterpreterInfo current() {
        MemorySegment interpreter = PyInterpreterState_Get();
        MemorySegment threadState = PyThreadState_Get();
        // Sanity check: the attached thread state belongs to this interpreter.
        if (!PyThreadState_GetInterpreter(threadState).equals(interpreter)) {
            throw new IllegalStateException("Thread state does not belong to the current interpreter");
        }
        // Borrowed reference, or NULL (without an exception set) if there is none.
        MemorySegment dict = PyInterpreterState_GetDict(interpreter);
        return new InterpreterInfo(
                PyInterpreterState_GetID(interpreter),
                interpreter.equals(PyInterpreterState_Main()),
                interpreter.address(),
                PyThreadState_GetID(threadState),
                dict.equals(MemorySegment.NULL) ? OptionalLong.empty() : OptionalLong.of(PyDict_Size(dict)));
    }

    @Override
    public String toString() {
        return "interpreter " + id + (main ? " (main)" : "")
                + " at 0x" + Long.toHexString(stateAddress)
                + ", thread state " + threadStateId
                + ", interpreter dict " + (dictSize.isPresent() ? dictSize.getAsLong() + " entries" : "absent");
    }
}
