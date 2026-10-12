import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

/**
 * Supplies `-Djava.library.path=...` lazily, so the directory can come from a
 * provider (for example a `python` sysconfig query) that is only evaluated
 * when the JVM is actually launched.
 */
class JavaLibraryPath(@get:Input val directory: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Djava.library.path=${directory.get()}")
}
