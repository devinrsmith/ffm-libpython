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
     * {@code Py_TYPE} before 3.14, returning a <b>borrowed</b> reference as
     * {@code Py_TYPE} does.
     *
     * <p>{@code PyObject_Type} returns a <em>new</em> reference to the same type.
     * Releasing it at once turns it into a borrowed one: the object holds a strong
     * reference to its type, so the type stays alive as long as the object does
     * (and keeps its type: assigning {@code __class__} would drop that reference,
     * exactly as for the real {@code Py_TYPE}'s result).
     */
    static PyObject.Type pyTypeFallback(PyObject object) {
        PyObject.Type type = new PyObject.Type(PyObject_Type(object.segment()));
        Py.decRef(type); // our extra reference; the object's own keeps the type alive
        return type;
    }

    /** {@code Py_IS_TYPE} before 3.15: the header inline's definition. */
    static boolean pyIsTypeFallback(PyObject object, PyObject.Type type) {
        return pyType(object).equals(type);
    }
}
