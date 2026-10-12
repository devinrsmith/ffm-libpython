plugins {
    application
    id("ffm-proto.jextract")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// --- Locating libpython ------------------------------------------------------
// Ask the target interpreter where its headers and shared library live, rather
// than hard-coding distro-specific paths. Evaluated lazily, only by tasks that
// need it (jextract, run, test).

val pythonExecutable = providers.gradleProperty("python.executable")

fun pythonSysconfig(expression: String): Provider<String> =
    providers.exec {
        commandLine(pythonExecutable.get(), "-c", "import sysconfig; print($expression)")
    }.standardOutput.asText.map { it.trim() }

val pythonIncludeDir = pythonSysconfig("sysconfig.get_path('include')")
val pythonLibDir = pythonSysconfig("sysconfig.get_config_var('LIBDIR')")

// --- Bindings ----------------------------------------------------------------

tasks.jextract {
    // dlfcn.h supplies dladdr/dlopen, used to make libpython's symbols global.
    headers = listOf("<Python.h>", "<dlfcn.h>")
    defines.add("_GNU_SOURCE") // for dladdr and Dl_info
    symbolsFile = layout.projectDirectory.file("jextract/capi.symbols")
    targetPackage = "com.example.ffm.python.capi"
    headerClassName = "CApi"
    // Generates System.loadLibrary("python3.12"), so the library is found via
    // java.library.path (set below) rather than only the dynamic linker's path.
    libraries.add("python3.12")
    useSystemLoadLibrary = true
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

tasks.run {
    jvmArgumentProviders.add(JavaLibraryPath(pythonLibDir))
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            targets.configureEach {
                testTask {
                    // Tests run on the classpath (there is no test module-info).
                    jvmArgs("--enable-native-access=ALL-UNNAMED")
                    jvmArgumentProviders.add(JavaLibraryPath(pythonLibDir))
                    testLogging {
                        events("passed", "skipped", "failed")
                        showStandardStreams = true
                    }
                }
            }
        }
    }
}
