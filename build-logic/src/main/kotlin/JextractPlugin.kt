import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register

/**
 * Registers a `jextract` task and adds its output directory to the `main`
 * source set.
 *
 * The generated bindings live in `src/main/jextract` and are committed, so an
 * ordinary build needs only a JDK. Run `./gradlew jextract` to regenerate
 * them; that additionally requires jextract and the C headers.
 */
class JextractPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val generatedSources = project.layout.projectDirectory.dir("src/main/jextract")

        project.tasks.register<JextractTask>("jextract") {
            group = "build setup"
            description = "Regenerates the checked-in FFM bindings with jextract."
            executable.convention(project.providers.gradleProperty("jextract.executable").orElse("jextract"))
            useSystemLoadLibrary.convention(false)
            defines.convention(emptyList())
            outputDirectory.convention(generatedSources)
        }

        project.plugins.withType(JavaPlugin::class.java) {
            project.extensions.getByName<SourceSetContainer>("sourceSets").named("main") {
                // A plain directory, not the task's output provider: compiling must
                // not trigger (and so require) jextract.
                java.srcDir(generatedSources)
            }
        }
    }
}
