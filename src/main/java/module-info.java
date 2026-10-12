/**
 * Embeds CPython 3.12 using the Foreign Function &amp; Memory API.
 *
 * <p>The low-level bindings in {@code com.example.ffm.python.capi} are generated
 * by jextract and stay internal to this module; {@link com.example.ffm.python.EmbeddedPython}
 * is the supported entry point.
 */
module com.example.ffm.python {
    exports com.example.ffm.python;
}
