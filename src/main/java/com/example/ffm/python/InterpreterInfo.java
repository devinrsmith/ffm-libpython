package com.example.ffm.python;

import com.example.ffm.python.internal.Py312;
import com.example.ffm.python.internal.PyInterpreterState;
import com.example.ffm.python.internal.PyObject;
import com.example.ffm.python.internal.PyThreadState;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Diagnostics about one CPython interpreter (main or sub-interpreter), from the
 * {@code PyInterpreterState_Get*()} family.
 *
 * @param id {@code PyInterpreterState_GetID()}: unique within the process, never
 *     reused; the main interpreter's is {@code 0}
 * @param main whether this is {@code PyInterpreterState_Main()}
 * @param stateAddress the address of the {@code PyInterpreterState}, from
 *     {@code PyInterpreterState_Get()}. Only distinguishes interpreters alive at the
 *     same time: CPython reuses the memory, so interpreters created one after
 *     another often get the same address. Use {@link #id()} for identity.
 * @param threadStateId {@code PyThreadState_GetID()} of the thread state used to
 *     reach the interpreter, i.e. the one owned by the {@code EmbeddedPython} or
 *     {@code SubInterpreter}. Numbered per interpreter, so typically {@code 1}
 * @param dictSize the number of entries in {@code PyInterpreterState_GetDict()},
 *     where extensions keep per-interpreter data. CPython creates that dict on
 *     first request (so reading this diagnostic allocates it); empty only if that
 *     allocation failed
 */
public record InterpreterInfo(long id, boolean main, long stateAddress, long threadStateId, OptionalLong dictSize) {

    /**
     * Reads the diagnostics of the interpreter the calling thread is attached to.
     *
     * <p>None of the pointers involved is a new reference. The interpreter and
     * thread states are not refcounted at all; they stay valid while this thread is
     * attached (and the main interpreter while any interpreter is). The interpreter
     * dict is borrowed from the interpreter, which owns it, and used only by
     * {@code PyDict_Size}, which runs no Python code, so it cannot be freed between
     * the two calls.
     */
    static InterpreterInfo current() {
        PyInterpreterState interpreter = PyInterpreterState.current();
        PyThreadState threadState = PyThreadState.current();
        // Sanity check: the attached thread state belongs to this interpreter.
        if (!threadState.interpreter().equals(interpreter)) {
            throw new IllegalStateException("Thread state does not belong to the current interpreter");
        }
        // Borrowed from the interpreter (created on first use); empty only if CPython
        // could not allocate it.
        Optional<PyObject.Dict> dict = interpreter.dict();
        return new InterpreterInfo(
                interpreter.id(),
                interpreter.equals(Py312.interpreterStateMain()),
                interpreter.segment().address(),
                threadState.id(),
                dict.stream().mapToLong(PyObject.Dict::size).findFirst());
    }

    @Override
    public String toString() {
        return "interpreter " + id + (main ? " (main)" : "")
                + " at 0x" + Long.toHexString(stateAddress)
                + ", thread state " + threadStateId
                + ", interpreter dict " + (dictSize.isPresent() ? dictSize.getAsLong() + " entries" : "absent");
    }
}
