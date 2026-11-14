package cc.ptoe.easytier.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Builds `libeasytier_android_jni.so` from the `EasyTier-Core` git submodule with
 * `cargo-ndk` and writes it to `<outputDir>/<abi>/`.
 *
 * Nothing is installed by this task. Missing prerequisites (rustup targets,
 * cargo-ndk, protoc, the pinned Rust toolchain) abort the build with the exact
 * command the developer has to run.
 *
 * `easytier-ffi` is a path dependency of `easytier-android-jni`, so it is compiled
 * as part of the JNI crate (the app only ships the JNI library; a separate
 * `libeasytier_ffi.so` is not used).
 */
@DisableCachingByDefault(because = "Output depends on host toolchains outside Gradle's control")
abstract class BuildRustJniLibsTask : DefaultTask() {

    /** Root of the EasyTier-Core checkout (the git submodule). */
    @get:Internal
    abstract val coreDir: DirectoryProperty

    /** Rust sources, manifests, lock file and toolchain pin of the core. Drives up-to-date checks. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val coreSources: ConfigurableFileCollection

    /** Commit of the submodule as recorded by the superproject (`git rev-parse HEAD:EasyTier-Core`). */
    @get:Input
    abstract val coreCommit: Property<String>

    /** Android ABIs to build: any of arm64-v8a, armeabi-v7a, x86_64. */
    @get:Input
    abstract val abis: ListProperty<String>

    /** NDK directory resolved by AGP from `android.ndkVersion`; exported as ANDROID_NDK_HOME. */
    @get:Input
    abstract val ndkDir: Property<String>

    /** `*.patch` files (relative to `patches/`) to apply to the core before building. */
    @get:Input
    abstract val patchNames: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val patchFiles: ConfigurableFileCollection

    /** CARGO_TARGET_DIR. Kept outside the submodule so it stays clean. */
    @get:Internal
    abstract val cargoTargetDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Provenance record (core commit, rustc, cargo-ndk, NDK); kept out of the jniLibs tree. */
    @get:OutputFile
    abstract val buildInfoFile: RegularFileProperty

    @get:Inject
    protected abstract val execOps: ExecOperations

    private val abiToTriple = mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
    )

    private fun run(
        cmd: List<String>,
        dir: File,
        extraEnv: Map<String, String> = emptyMap(),
        ignoreExit: Boolean = false,
        capture: Boolean = false,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(cmd)
            workingDir = dir
            environment(extraEnv)
            isIgnoreExitValue = true
            if (capture) {
                standardOutput = out
                errorOutput = out
            }
        }
        if (!ignoreExit && result.exitValue != 0) {
            throw GradleException("Command failed (exit ${result.exitValue}): ${cmd.joinToString(" ")}")
        }
        return result.exitValue to out.toString().trim()
    }

    private fun tryCapture(cmd: List<String>, dir: File, env: Map<String, String>): String? =
        try {
            val (code, out) = run(cmd, dir, env, ignoreExit = true, capture = true)
            if (code == 0) out else null
        } catch (_: Exception) {
            null
        }

    @TaskAction
    fun build() {
        val core = coreDir.get().asFile
        if (!File(core, "easytier-contrib/easytier-android-jni/Cargo.toml").exists()) {
            throw GradleException(
                "EasyTier-Core submodule is not initialised.\n" +
                    "Run: git submodule update --init --recursive"
            )
        }
        val abiList = abis.get()
        val unknown = abiList.filterNot { it in abiToTriple }
        if (unknown.isNotEmpty()) {
            throw GradleException("Unsupported ABI(s): $unknown. Supported: ${abiToTriple.keys}")
        }

        val ndk = File(ndkDir.get())
        val env = mutableMapOf(
            "ANDROID_NDK_HOME" to ndk.absolutePath,
            "CARGO_TARGET_DIR" to cargoTargetDir.get().asFile.absolutePath,
            // Never let rustup download a toolchain behind the developer's back.
            "RUSTUP_AUTO_INSTALL" to "0",
        )
        val ndkLlvmLib = File(ndk, "toolchains/llvm/prebuilt").listFiles()
            ?.firstOrNull { it.isDirectory }?.resolve("lib")
        if (ndkLlvmLib != null && ndkLlvmLib.listFiles()?.any { it.name.startsWith("libclang") } == true &&
            System.getenv("LIBCLANG_PATH") == null
        ) {
            // bindgen (kcp-sys) needs libclang on the host; the NDK ships one.
            env["LIBCLANG_PATH"] = ndkLlvmLib.absolutePath
        }

        checkPrerequisites(core, abiList, env)

        // Optional patches (off by default; see patches/README.md).
        val patches = patchNames.get().map { name ->
            val f = patchFiles.files.firstOrNull { it.name == name }
                ?: throw GradleException("Patch '$name' not found in patches/")
            f
        }
        val applied = mutableListOf<File>()
        val out = outputDir.get().asFile
        try {
            for (p in patches) {
                logger.lifecycle("Applying patch ${p.name}")
                run(listOf("git", "apply", "--whitespace=nowarn", p.absolutePath), core)
                applied += p
            }

            out.deleteRecursively()
            out.mkdirs()
            for (abi in abiList) {
                logger.lifecycle("[$abi] cargo ndk build --release --package easytier-android-jni")
                run(
                    listOf(
                        "cargo", "ndk", "--target", abi, "--output-dir", out.absolutePath,
                        "build", "--release", "--locked", "--package", "easytier-android-jni",
                    ),
                    core,
                    env,
                )
                val so = File(out, "$abi/libeasytier_android_jni.so")
                if (!so.isFile || so.length() == 0L) {
                    throw GradleException("Expected build output missing or empty: $so")
                }
                logger.lifecycle("[$abi] ${so.name}: ${so.length()} bytes")
            }
            // cargo-ndk copies every cdylib it built; the app only needs the JNI library.
            out.walkTopDown().filter { it.isFile && it.name != "libeasytier_android_jni.so" }
                .forEach { it.delete() }
            writeBuildInfo(core, env, abiList, patches)
        } finally {
            for (p in applied.asReversed()) {
                run(listOf("git", "apply", "-R", "--whitespace=nowarn", p.absolutePath), core, ignoreExit = true)
            }
        }
    }

    private fun checkPrerequisites(core: File, abiList: List<String>, env: Map<String, String>) {
        val problems = mutableListOf<String>()

        val rustc = tryCapture(listOf("rustc", "--version"), core, env)
        if (rustc == null) {
            problems += "Rust toolchain not available for EasyTier-Core (rust-toolchain.toml pins it).\n" +
                "    Install it with:  (cd EasyTier-Core && rustup toolchain install)"
        } else {
            val installed = tryCapture(listOf("rustup", "target", "list", "--installed"), core, env)
                ?.lines().orEmpty()
            if (installed.isNotEmpty()) {
                val missing = abiList.map { abiToTriple.getValue(it) }.filterNot { it in installed }
                if (missing.isNotEmpty()) {
                    problems += "Missing rustup targets for $rustc: ${missing.joinToString()}\n" +
                        "    Install with:  (cd EasyTier-Core && rustup target add ${missing.joinToString(" ")})"
                }
            }
        }
        if (tryCapture(listOf("cargo", "ndk", "--version"), core, env) == null) {
            problems += "cargo-ndk is not installed.\n    Install with:  cargo install cargo-ndk --locked"
        }
        val protoc = System.getenv("PROTOC")?.let { File(it).takeIf(File::isFile)?.absolutePath }
            ?: tryCapture(listOf("protoc", "--version"), core, env)
        if (protoc == null) {
            problems += "protoc not found (needed by prost build scripts).\n" +
                "    Install it from your package manager (protobuf-compiler / protobuf) or set PROTOC."
        }
        if (!File(env.getValue("ANDROID_NDK_HOME")).isDirectory) {
            problems += "Android NDK not found at ${env["ANDROID_NDK_HOME"]}."
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Cannot build the EasyTier native library. Fix the following and retry " +
                    "(or pass -PskipRustBuild -PrustJniLibsDir=<dir> to use a prebuilt directory):\n\n" +
                    problems.joinToString("\n\n") { "  * $it" }
            )
        }
    }

    private fun writeBuildInfo(
        core: File,
        env: Map<String, String>,
        abiList: List<String>,
        patches: List<File>,
    ) {
        val info = buildString {
            appendLine("easytier-core-commit=${coreCommit.get()}")
            appendLine("rustc=${tryCapture(listOf("rustc", "--version", "--verbose"), core, env)?.lines()?.joinToString("; ")}")
            appendLine("cargo-ndk=${tryCapture(listOf("cargo", "ndk", "--version"), core, env)}")
            appendLine("ndk=${File(env.getValue("ANDROID_NDK_HOME")).name}")
            appendLine("abis=${abiList.joinToString(",")}")
            appendLine("patches=${patches.joinToString(",") { it.name }}")
        }
        buildInfoFile.get().asFile.apply { parentFile.mkdirs() }.writeText(info)
    }
}
