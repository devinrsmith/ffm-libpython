package com.example.ffm.python.internal;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Finds modules the way {@code importlib.util.find_spec} does, but without importing
 * anything, not even the parent packages of a dotted name (which {@code find_spec}
 * imports, running their {@code __init__}).
 *
 * <p>Each level of {@code a.b.c} is resolved by asking the finders on
 * {@code sys.meta_path} in turn, as {@code importlib} does, passing the previous
 * level's {@code submodule_search_locations}. Using {@code sys.meta_path} rather than
 * {@code PathFinder} alone also finds modules from editable installs, which register
 * their own finders. Must be called while attached to an interpreter.
 */
public final class ModuleFinder {

    private ModuleFinder() {}

    /**
     * The file of extension module {@code fullName}, if found.
     *
     * @throws IllegalArgumentException if {@code fullName} is not a module name, or
     *     the module is found but is not an extension module (pure Python, built in,
     *     ...)
     */
    public static Optional<Path> findExtensionModule(String fullName) {
        Optional<NewReference<PyObject.Any>> found = findSpec(fullName);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try (NewReference<PyObject.Any> spec = found.get();
                NewReference<PyObject.Any> loader = spec.get().getAttr("loader");
                NewReference<PyObject.Module> machinery = PyObject.Module.importModule("importlib.machinery");
                NewReference<PyObject.Any> extensionFileLoader = machinery.get().getAttr("ExtensionFileLoader")) {
            if (!loader.get().isInstance(extensionFileLoader.get())) {
                throw new IllegalArgumentException(fullName + " is not an extension module: its loader is " + typeName(loader.get()));
            }
            try (NewReference<PyObject.Any> origin = spec.get().getAttr("origin")) {
                return Optional.of(Path.of(origin.get().asStr()
                        .orElseThrow(() -> new IllegalStateException(fullName + "'s spec.origin is not a str"))
                        .value()));
            }
        }
    }

    /**
     * The module spec of {@code fullName} (a <b>new</b> reference, to be closed), if
     * any finder on {@code sys.meta_path} finds it; imports nothing.
     *
     * @throws IllegalArgumentException if {@code fullName} is not a module name
     */
    public static Optional<NewReference<PyObject.Any>> findSpec(String fullName) {
        String[] parts = fullName.split("\\.", -1);
        if (Arrays.stream(parts).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Not a module name: \"" + fullName + "\"");
        }
        // The previous level's spec.submodule_search_locations; null at the top level.
        NewReference<PyObject.Any> searchLocations = null;
        try {
            for (int level = 0; level < parts.length; level++) {
                String name = String.join(".", Arrays.copyOf(parts, level + 1));
                Optional<NewReference<PyObject.Any>> spec =
                        findOnMetaPath(name, searchLocations == null ? null : searchLocations.get());
                if (spec.isEmpty() || level == parts.length - 1) {
                    return spec;
                }
                try (NewReference<PyObject.Any> parentSpec = spec.get()) {
                    NewReference<PyObject.Any> locations = parentSpec.get().getAttr("submodule_search_locations");
                    if (searchLocations != null) {
                        searchLocations.close();
                    }
                    searchLocations = locations;
                    if (locations.get().isNone()) {
                        return Optional.empty(); // a module, not a package: no submodules
                    }
                }
            }
            throw new AssertionError("unreachable");
        } finally {
            if (searchLocations != null) {
                searchLocations.close();
            }
        }
    }

    /**
     * {@code importlib._bootstrap._find_spec}'s loop: the first non-None
     * {@code finder.find_spec(name, path, target)} on {@code sys.meta_path}, a
     * <b>new</b> reference. Like importlib, passes all three arguments: {@code path}
     * is None for a top-level module, and {@code target} (a module being reloaded)
     * is always None here.
     */
    private static Optional<NewReference<PyObject.Any>> findOnMetaPath(String name, PyObject path) {
        try (NewReference<PyObject.Module> sys = PyObject.Module.importModule("sys");
                NewReference<PyObject.Any> metaPath = sys.get().getAttr("meta_path");
                NewReference<PyObject.Str> pyName = PyObject.Str.of(name)) {
            PyObject.List finders = metaPath.get().asList()
                    .orElseThrow(() -> new IllegalStateException("sys.meta_path is not a list"));
            // Size re-read each time: a finder may change sys.meta_path.
            for (long i = 0; i < finders.size(); i++) {
                // Borrowed from sys.meta_path, which the finder we are about to call
                // may change: hold our own reference while using it.
                try (NewReference<PyObject.Any> finder = NewReference.incRef(finders.get(i))) {
                    if (!finder.get().hasAttr("find_spec")) {
                        continue; // as importlib does
                    }
                    NewReference<PyObject.Any> spec;
                    try (NewReference<PyObject.Any> findSpec = finder.get().getAttr("find_spec")) {
                        PyObject none = PyObject.Any.none();
                        spec = findSpec.get().call(pyName.get(), path == null ? none : path, none);
                    }
                    if (!spec.get().isNone()) {
                        return Optional.of(spec);
                    }
                    spec.close();
                }
            }
            return Optional.empty();
        }
    }

    /**
     * For messages: a loader's name. Some loaders are classes (e.g.
     * {@code BuiltinImporter}), others instances (e.g. {@code SourceFileLoader}).
     */
    private static String typeName(PyObject loader) {
        PyObject named = loader.isInstance(PyObject.Type.typeType()) ? loader : loader.type();
        try (NewReference<PyObject.Any> qualname = named.getAttr("__qualname__")) {
            return qualname.get().asStr().map(PyObject.Str::value).orElse("?");
        }
    }
}
