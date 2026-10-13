import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Adds `src/main/jextract` to the `main` source set, gives every [JextractTask]
 * conventions that generate into it, and registers a `jextract` lifecycle task
 * that runs them all.
 *
 * The generated bindings are committed, so an ordinary build needs only a JDK.
 * Run `./gradlew jextract` to regenerate them; that additionally requires
 * jextract and the C headers.
 */
class JextractPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val generatedSources = project.layout.projectDirectory.dir("src/main/jextract")
        val jextractTasks = project.tasks.withType<JextractTask>()

        jextractTasks.configureEach {
            group = "build setup"
            executable.convention(
                project.providers.executableOnPath(
                    project.providers.gradleProperty("jextract.executable").orElse("jextract")
                )
            )
            useSystemLoadLibrary.convention(false)
            defines.convention(emptyList())
            libraries.convention(emptyList())
            sourceRoot.convention(generatedSources)
        }

        project.tasks.register("jextract") {
            group = "build setup"
            description = "Regenerates all checked-in FFM bindings with jextract."
            dependsOn(jextractTasks)
        }

        project.plugins.withType(JavaPlugin::class.java) {
            project.extensions.getByName<SourceSetContainer>("sourceSets").named("main") {
                // A plain directory, not the tasks' outputs: compiling must not
                // trigger (and so require) jextract.
                java.srcDir(generatedSources)
            }
            // ...but when both are requested (`./gradlew jextract build`), compile
            // the freshly generated sources rather than racing their regeneration.
            project.tasks.withType<JavaCompile>().configureEach { mustRunAfter(jextractTasks) }
            project.tasks.withType<Javadoc>().configureEach { mustRunAfter(jextractTasks) }
        }
    }
}
