import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.io.File

/**
 * Resolves a command name against the invoking shell's `PATH`.
 *
 * Gradle looks up bare command names on the `PATH` of the long-lived daemon
 * process, which is fixed when the daemon starts. After switching shells (e.g.
 * a different `nix shell`), commands that are on the new `PATH` would not be
 * found. Resolving them up front against the client's `PATH` avoids that, and
 * makes `PATH` a tracked configuration cache input.
 *
 * Names containing a path separator are returned unchanged, as are names not
 * found on the `PATH` (so the eventual error names the command).
 */
fun ProviderFactory.executableOnPath(name: Provider<String>): Provider<String> =
    name.zip(environmentVariable("PATH").orElse("")) { command, path ->
        if (command.contains(File.separatorChar)) {
            command
        } else {
            path.split(File.pathSeparator)
                .filter(String::isNotEmpty)
                .map { File(it, command) }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
                ?: command
        }
    }
