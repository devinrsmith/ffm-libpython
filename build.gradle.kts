plugins {
    application
    id("ffm-proto.jextract")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// --- Locating Python ---------------------------------------------------------
// The bindings are generated from the headers of the interpreter named by
// `jextract.python` (the oldest supported version). Evaluated lazily, only when
// the jextract task runs.

val pythonIncludeDir = providers.exec {
    commandLine(
        providers.gradleProperty("jextract.python").get(),
        "-c", "import sysconfig; print(sysconfig.get_path('include'))",
    )
}.standardOutput.asText.map { it.trim() }

// --- Bindings ----------------------------------------------------------------

tasks.jextract {
    // dlfcn.h supplies dlopen, used to make libpython's symbols global.
    headers = listOf("<Python.h>", "<dlfcn.h>")
    // Restrict Python.h to the Stable ABI as of 3.12, which every later 3.x
    // libpython keeps providing.
    defines.add("Py_LIMITED_API=0x030C0000")
    symbolsFile = layout.projectDirectory.file("jextract/capi.symbols")
    targetPackage = "com.example.ffm.python.capi"
    headerClassName = "CApi"
    // No --library: which libpython to load is decided at runtime (LibPython.java).
    includeDirs.add(pythonIncludeDir)
    // Extra -I paths for environments where libclang cannot find the C
    // standard headers by itself (e.g. Nix), separated by the path separator.
    includeDirs.addAll(
        providers.gradleProperty("jextract.includeDirs")
            .map { dirs -> dirs.split(File.pathSeparator).filter(String::isNotBlank) }
            .orElse(emptyList())
    )
}

// --- Compilation, running and testing ----------------------------------------

tasks.withType<JavaCompile>().configureEach {
    // "restricted" flags every FFM call into native code; that is the point of
    // this project, and it is gated at runtime by --enable-native-access instead.
    options.compilerArgs.addAll(listOf("-Xlint:all,-restricted", "-Werror"))
}

application {
    mainModule = "com.example.ffm.python"
    mainClass = "com.example.ffm.python.Main"
    // Calling native code is a restricted operation; grant it to our module only.
    applicationDefaultJvmArgs = listOf("--enable-native-access=com.example.ffm.python")
}

// Forward the runtime's libpython selection, e.g. `./gradlew run -Ppython.executable=python3.14`.
val pythonRuntimeProperties = listOf("python.executable", "python.library")
    .mapNotNull { name -> providers.gradleProperty(name).orNull?.let { name to it } }
    .toMap()

tasks.run {
    systemProperties(pythonRuntimeProperties)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            targets.configureEach {
                testTask {
                    // Tests run on the classpath (there is no test module-info).
                    jvmArgs("--enable-native-access=ALL-UNNAMED")
                    systemProperties(pythonRuntimeProperties)
                    testLogging {
                        events("passed", "skipped", "failed")
                        showStandardStreams = true
                    }
                }
            }
        }
    }
}
