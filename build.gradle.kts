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

// Two lines: the headers' major.minor version, then their directory.
val pythonHeaders = providers.exec {
    commandLine(
        providers.executableOnPath(providers.gradleProperty("jextract.python")).get(),
        "-c", "import sysconfig; print(sysconfig.get_python_version()); print(sysconfig.get_path('include'))",
    )
}.standardOutput.asText.map { it.trim().lines() }
val pythonIncludeDir = pythonHeaders.map { it[1] }

/** The include directory, provided the headers are exactly Python [version]. */
fun pythonIncludeDirFor(version: String): Provider<String> = pythonHeaders.map { (actual, dir) ->
    check(actual == version) {
        "These bindings must be generated from the Python $version headers, but " +
            "jextract.python is Python $actual; point it at a $version interpreter"
    }
    dir
}

// --- Bindings ----------------------------------------------------------------

// No --library in either: which libpython to load is decided at runtime
// (LibPython.java), and no other library is needed.
tasks.withType<JextractTask>().configureEach {
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
    // Py_LIMITED_API pins the API level, so newer headers give equivalent bindings
    // (only the declaration order differs); 3.12 keeps the committed output stable.
    includeDirs.add(pythonIncludeDir)
}

// CPython's version-specific API, for what the Stable ABI lacks, as declared
// by the 3.12 headers. Kept to a minimum, and only symbols whose declarations
// and layouts are unchanged in every supported version (see README).
tasks.register<JextractTask>("jextractCPython312") {
    description = "Generates the CPython 3.12 version-specific bindings (capi.py312)."
    headers = listOf("<Python.h>")
    symbolsFile = layout.projectDirectory.file("jextract/cpython312.symbols")
    targetPackage = "com.example.ffm.python.capi.py312"
    headerClassName = "CPython312"
    // Only 3.12 headers: generating these from any other version would mislabel them.
    includeDirs.add(pythonIncludeDirFor("3.12"))
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
