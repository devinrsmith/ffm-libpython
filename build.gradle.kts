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
// a jextract task runs.

val pythonIncludeDir = providers.exec {
    commandLine(
        providers.executableOnPath(providers.gradleProperty("jextract.python")).get(),
        "-c", "import sysconfig; print(sysconfig.get_path('include'))",
    )
}.standardOutput.asText.map { it.trim() }

// --- Bindings ----------------------------------------------------------------

// No --library in either: which libpython to load is decided at runtime
// (LibPython.java), and no other library is needed.
tasks.withType<JextractTask>().configureEach {
    includeDirs.add(pythonIncludeDir)
    // Extra -I paths for environments where libclang cannot find the C
    // standard headers by itself (e.g. Nix), separated by the path separator.
    includeDirs.addAll(
        providers.gradleProperty("jextract.includeDirs")
            .map { dirs -> dirs.split(File.pathSeparator).filter(String::isNotBlank) }
            .orElse(emptyList())
    )
}

// The Stable ABI: what every libpython >= 3.12 provides.
tasks.register<JextractTask>("jextractStableAbi") {
    description = "Generates the Stable ABI bindings (capi.stable)."
    // dlfcn.h supplies dlopen, used to make libpython's symbols global.
    headers = listOf("<Python.h>", "<dlfcn.h>")
    // Restrict Python.h to the Stable ABI as of 3.12, which every later 3.x
    // libpython keeps providing.
    defines.add("Py_LIMITED_API=0x030C0000")
    symbolsFile = layout.projectDirectory.file("jextract/stable.symbols")
    targetPackage = "com.example.ffm.python.capi.stable"
    headerClassName = "CApi"
}

// CPython's version-specific API, for what the Stable ABI lacks. Kept to a
// minimum, and only symbols whose layout is identical from 3.12 on (see README).
tasks.register<JextractTask>("jextractCPython") {
    description = "Generates the version-specific CPython bindings (capi.cpython)."
    headers = listOf("<Python.h>")
    symbolsFile = layout.projectDirectory.file("jextract/cpython.symbols")
    targetPackage = "com.example.ffm.python.capi.cpython"
    headerClassName = "CPython"
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

// The tests embed a virtual environment made from the selected interpreter
// (-Ppython.executable, default python3), to check that it is picked up.
val testVenv = layout.buildDirectory.dir("test-venv")
val createTestVenv = tasks.register<Exec>("createTestVenv") {
    description = "Creates the virtual environment the tests embed."
    val python = providers.executableOnPath(providers.gradleProperty("python.executable").orElse("python3"))
    inputs.property("python", python)
    outputs.dir(testVenv)
    commandLine(python.get(), "-m", "venv", "--clear", testVenv.get().asFile.absolutePath)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            targets.configureEach {
                testTask {
                    // Tests run on the classpath (there is no test module-info).
                    jvmArgs("--enable-native-access=ALL-UNNAMED")
                    // CPython can only be initialized once per process: give each
                    // test class its own JVM.
                    forkEvery = 1
                    dependsOn(createTestVenv)
                    val venv = testVenv.get().asFile.absolutePath
                    systemProperty("python.executable", "$venv/bin/python")
                    systemProperty("test.venv", venv)
                    testLogging {
                        events("passed", "skipped", "failed")
                        showStandardStreams = true
                    }
                }
            }
        }
    }
}
