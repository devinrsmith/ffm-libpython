import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
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

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

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

        // Sync rather than copy, so bindings for removed symbols disappear too.
        fileSystemOperations.sync {
            from(stagingDir)
            into(outputDirectory)
        }
    }
}
