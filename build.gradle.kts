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
// Each set of bindings is generated from the headers of exactly the Python
// version in its name, found via the interpreter in the Gradle property
// `jextract.pythonXYZ` (see gradle.properties). Evaluated lazily, only when
// a jextract task runs.

/**
 * The include directory of the interpreter in `jextract.python<version without dot>`,
 * provided its headers are exactly Python [version].
 */
fun pythonIncludeDir(version: String): Provider<String> {
    val property = "jextract.python" + version.replace(".", "")
    return providers.exec {
        commandLine(
            providers.executableOnPath(providers.gradleProperty(property)).get(),
            "-c", "import sysconfig; print(sysconfig.get_python_version()); print(sysconfig.get_path('include'))",
        )
    }.standardOutput.asText.map { output ->
        val (actual, dir) = output.trim().lines()
        check(actual == version) {
            "These bindings must be generated from the Python $version headers, but " +
                "$property is Python $actual; point it at a $version interpreter"
        }
        dir
    }
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

// The Stable ABI at the 3.12 level: what every libpython >= 3.12 provides.
tasks.register<JextractTask>("jextractStable312") {
    description = "Generates the Stable ABI bindings at the 3.12 level (capi.stable312)."
    // dlfcn.h supplies dlopen, used to make libpython's symbols global.
    headers = listOf("<Python.h>", "<dlfcn.h>")
    // Restrict Python.h to the Stable ABI as of 3.12.
    defines.add("Py_LIMITED_API=0x030C0000")
    symbolsFile = layout.projectDirectory.file("jextract/stable312.symbols")
    targetPackage = "com.example.ffm.python.capi.stable312"
    headerClassName = "Stable312"
    includeDirs.add(pythonIncludeDir("3.12"))
}

// What the Stable ABI added in 3.13, for code that switches on the loaded
// library's version: only callable when Py_Version >= 3.13 (see README).
tasks.register<JextractTask>("jextractStable313") {
    description = "Generates the Stable ABI bindings added at the 3.13 level (capi.stable313)."
    headers = listOf("<Python.h>")
    defines.add("Py_LIMITED_API=0x030D0000")
    symbolsFile = layout.projectDirectory.file("jextract/stable313.symbols")
    targetPackage = "com.example.ffm.python.capi.stable313"
    headerClassName = "Stable313"
    includeDirs.add(pythonIncludeDir("3.13"))
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
    includeDirs.add(pythonIncludeDir("3.12"))
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
