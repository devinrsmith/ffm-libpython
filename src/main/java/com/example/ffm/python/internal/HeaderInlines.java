package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable312.Stable312.PyObject_Type;
import static com.example.ffm.python.capi.stable314.Stable314.Py_TYPE;
import static com.example.ffm.python.capi.stable315.Stable315.Py_IS_TYPE;

/**
 * CPython functions that were {@code static inline} in the headers (so not
 * exported, and not callable through FFM) before the Stable ABI turned them into
 * exported functions.
 *
 * <p>Each one calls the exported function where the loaded libpython has it, and
 * before that reproduces the header inline's definition, using functions every
 * supported version exports:
 *
 * <table>
 *   <caption>Stabilized header inlines</caption>
 *   <tr><th>Function</th><th>Exported since</th><th>Before that</th></tr>
 *   <tr><td>{@code Py_TYPE}</td><td>3.14</td><td>{@code PyObject_Type} + {@code Py_DecRef}</td></tr>
 *   <tr><td>{@code Py_IS_TYPE}</td><td>3.15</td><td>{@code Py_TYPE(o) == type}</td></tr>
 * </table>
 *
 * <p>The fallbacks deliberately avoid what the inlines themselves do (read
 * {@code ob->ob_type}): that would tie the bindings to the {@code PyObject} struct
 * layout, which is what CPython made these functions to move away from.
 */
final class HeaderInlines {

    private HeaderInlines() {}

    /** {@code Py_TYPE(o)}: the object's type, <b>borrowed</b> from the object. */
    static PyObject.Type pyType(PyObject object) {
        return ApiLevel.atLeast(3, 14) ? new PyObject.Type(Py_TYPE(object.segment())) : pyTypeFallback(object);
    }

    /** {@code Py_IS_TYPE(o, type)}: whether the object's type is exactly {@code type}. */
    static boolean pyIsType(PyObject object, PyObject.Type type) {
        return ApiLevel.atLeast(3, 15) ? Py_IS_TYPE(object.segment(), type.segment()) != 0 : pyIsTypeFallback(object, type);
    }

    /**
     * {@code Py_TYPE} before 3.14. {@code PyObject_Type} returns a new reference to
     * the type; releasing it straight away leaves the same borrowed reference
     * {@code Py_TYPE} returns, kept alive by the object itself.
     */
    static PyObject.Type pyTypeFallback(PyObject object) {
        try (NewReference<PyObject.Type> type = NewReference.adopt(new PyObject.Type(PyObject_Type(object.segment())))) {
            return type.get();
        }
    }

    /** {@code Py_IS_TYPE} before 3.15: the header inline's definition. */
    static boolean pyIsTypeFallback(PyObject object, PyObject.Type type) {
        return pyType(object).equals(type);
    }
}
