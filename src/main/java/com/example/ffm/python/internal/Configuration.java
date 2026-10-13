package com.example.ffm.python.internal;

import static com.example.ffm.python.capi.stable312.Stable312.Py_GetBuildInfo;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetCompiler;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetExecPrefix;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetPath;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetPlatform;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetPrefix;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetProgramFullPath;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetProgramName;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetPythonHome;
import static com.example.ffm.python.capi.stable312.Stable312.Py_GetVersion;

import java.io.File;
import java.lang.foreign.MemorySegment;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * How the interpreter the calling thread is attached to was configured: its path
 * configuration and build information, keyed by name in a fixed order.
 *
 * <p>The path configuration comes from the deprecated {@code Py_Get*()} functions
 * up to 3.13, and from their replacement, {@code PyConfig_Get} (PEP 741), from 3.14
 * on, chosen at runtime. The {@code Py_Get*()} path functions were deprecated in
 * 3.13 and removed from the 3.15 headers; being Stable ABI, 3.15 still exports
 * them, but nothing promises they keep working.
 */
public final class Configuration {

    private Configuration() {}

    /** The path configuration, by {@code PyConfig_Get} name, then the build information. */
    public static SequencedMap<String, String> read() {
        SequencedMap<String, String> configuration = new LinkedHashMap<>();
        if (ApiLevel.atLeast(3, 14)) {
            configuration.put("program_name", configString("program_name"));
            configuration.put("executable", configString("executable"));
            configuration.put("home", configString("home"));
            configuration.put("prefix", configString("prefix"));
            configuration.put("exec_prefix", configString("exec_prefix"));
            configuration.put("module_search_paths", configPathList("module_search_paths"));
        } else {
            // Static wchar_t strings owned by CPython; NULL when unset.
            configuration.put("program_name", wide(Py_GetProgramName()));
            configuration.put("executable", wide(Py_GetProgramFullPath()));
            configuration.put("home", wide(Py_GetPythonHome()));
            configuration.put("prefix", wide(Py_GetPrefix()));
            configuration.put("exec_prefix", wide(Py_GetExecPrefix()));
            configuration.put("module_search_paths", wide(Py_GetPath()));
        }
        // Build information: static UTF-8 strings, not deprecated.
        configuration.put("version", utf8(Py_GetVersion()));
        configuration.put("platform", utf8(Py_GetPlatform()));
        configuration.put("compiler", utf8(Py_GetCompiler()));
        configuration.put("build_info", utf8(Py_GetBuildInfo()));
        return configuration;
    }

    /** A {@code str} option, or {@code null} for {@code None}. */
    private static String configString(String name) {
        try (NewReference<PyObject.Any> value = Py314.configGet(name)) {
            return value.get().asStr().map(PyObject.Str::value).orElse(null);
        }
    }

    /** A {@code list[str]} option, joined like {@code Py_GetPath()}. */
    private static String configPathList(String name) {
        try (NewReference<PyObject.Any> value = Py314.configGet(name)) {
            PyObject.List list = value.get().asList()
                    .orElseThrow(() -> new IllegalStateException(name + " is not a list"));
            // Items are borrowed from the list, which we hold; no Python code runs here.
            return LongStream.range(0, list.size())
                    .mapToObj(i -> list.get(i).asStr()
                            .orElseThrow(() -> new IllegalStateException(name + " has a non-str item"))
                            .value())
                    .collect(Collectors.joining(File.pathSeparator));
        }
    }

    private static String wide(MemorySegment string) {
        return string(string, WideChars.CHARSET);
    }

    private static String utf8(MemorySegment string) {
        return string(string, StandardCharsets.UTF_8);
    }

    private static String string(MemorySegment string, Charset charset) {
        // The bindings give char/wchar_t pointers an unbounded target layout, so the
        // NUL-terminated string can be read directly.
        return string.equals(MemorySegment.NULL) ? null : string.getString(0, charset);
    }
}
