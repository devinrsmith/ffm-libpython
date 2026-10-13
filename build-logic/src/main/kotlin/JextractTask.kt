import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs `jextract` to generate Java FFM bindings for C headers.
 *
 * Only the symbols listed in [symbolsFile] (a jextract argument file of
 * `--include-*` options, as produced by `jextract --dump-includes`) are
 * generated, which keeps the bindings small and reviewable.
 */
@UntrackedTask(because = "Regenerates checked-in sources on request; the real inputs are system headers")
abstract class JextractTask : DefaultTask() {

    /** The jextract launcher: a name on the PATH or an absolute path. */
    @get:Input
    abstract val executable: Property<String>

    /**
     * Headers to extract: file paths, or `<name.h>` to search the include
     * paths as `#include <name.h>` would.
     */
    @get:Input
    abstract val headers: ListProperty<String>

    /** Preprocessor definitions (`-D`), as `NAME` or `NAME=value`. */
    @get:Input
    abstract val defines: ListProperty<String>

    /** Include search paths (`-I`). */
    @get:Input
    abstract val includeDirs: ListProperty<String>

    /** Argument file of `--include-function` / `--include-struct` / ... options. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val symbolsFile: RegularFileProperty

    @get:Input
    abstract val targetPackage: Property<String>

    @get:Input
    @get:Optional
    abstract val headerClassName: Property<String>

    /** Libraries the generated header class loads (`-l`). */
    @get:Input
    abstract val libraries: ListProperty<String>

    /** Load [libraries] with `System.loadLibrary`, i.e. honour `java.library.path`. */
    @get:Input
    abstract val useSystemLoadLibrary: Property<Boolean>

    /**
     * The source root to generate into. Several tasks can share one: each only
     * replaces its own [targetPackage] directory within it.
     */
    @get:Internal
    abstract val sourceRoot: DirectoryProperty

    /** The directory of [targetPackage] under [sourceRoot]: what this task owns. */
    @get:OutputDirectory
    val packageDirectory: Provider<Directory>
        get() = sourceRoot.zip(targetPackage) { root, pkg -> root.dir(pkg.replace('.', '/')) }

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        // Generate into a scratch directory first, so a failed (or crashed) jextract
        // leaves the checked-in bindings untouched.
        val stagingDir = temporaryDir.resolve("output")
        fileSystemOperations.delete { delete(stagingDir) }

        execOperations.exec {
            executable(this@JextractTask.executable.get())
            args("--output", stagingDir.absolutePath)
            args("--target-package", targetPackage.get())
            headerClassName.orNull?.let { args("--header-class-name", it) }
            libraries.get().forEach { args("--library", it) }
            if (useSystemLoadLibrary.get()) args("--use-system-load-library")
            defines.get().forEach { args("--define-macro", it) }
            includeDirs.get().forEach { args("--include-dir", it) }
            args("@" + symbolsFile.get().asFile.absolutePath)
            args(headers.get())
        }

        // jextract skips requested symbols it cannot bind, silently if they are not
        // in the header view: fail instead, before touching the checked-in bindings.
        val symbols = symbolsFile.get().asFile
        val missing = missingSymbols(symbols, stagingDir)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "jextract did not generate ${missing.size} requested symbol(s) from ${symbols.name}:\n" +
                    missing.joinToString("\n") { "    $it" } + "\n" +
                    "  Possible causes: not in the headers" +
                    defines.get().filter { it.startsWith("Py_LIMITED_API") }.joinToString("") { ", hidden by $it" } +
                    ", or a type jextract cannot represent (look for \"Skipping <name>\" in its output above)."
            )
        }

        // Sync rather than copy, so bindings for removed symbols disappear too.
        val packagePath = targetPackage.get().replace('.', '/')
        fileSystemOperations.sync {
            from(stagingDir.resolve(packagePath))
            into(packageDirectory)
        }
    }
}

private val INCLUDE = Regex("""^--include-(function|var|constant|struct|union|typedef)\s+(\S+)""")

/**
 * The `--include-*` lines of [symbolsFile] that produced no declaration in the
 * Java sources under [outputDir]: a `public static ... name(` method for a
 * function, variable or constant; a class `name` for a struct or union; either for
 * a typedef (a class for a struct typedef, a `static final ... name` layout for a
 * primitive one).
 */
internal fun missingSymbols(symbolsFile: File, outputDir: File): List<String> {
    val sources = outputDir.walk().filter { it.isFile && it.extension == "java" }.toList()
    val text = sources.joinToString("\n") { it.readText() }
    val classes = sources.map { it.nameWithoutExtension }.toSet()
    return symbolsFile.readLines()
        .mapNotNull { INCLUDE.find(it.trim()) } // skips comments and blank lines
        .filterNot { match ->
            val (kind, name) = match.destructured
            val method = Regex("""public static [^;{=]*\b${Regex.escape(name)}\(""")
            when (kind) {
                "function", "var", "constant" -> method.containsMatchIn(text)
                "struct", "union" -> name in classes
                else -> name in classes ||
                    Regex("""static final [^;=]*\b${Regex.escape(name)}\b""").containsMatchIn(text)
            }
        }
        .map { it.value }
}
