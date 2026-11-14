import cc.ptoe.easytier.gradle.BuildRustJniLibsTask
import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

// Semantic version from version.txt (configuration-cache safe: lazy provider).
val semanticVersion: Provider<String> = providers.fileContents(
    rootProject.layout.projectDirectory.file("version.txt")
).asText.map { it.trim() }.filter { it.isNotEmpty() }.orElse("0.0.0")

// Git commit count as numeric version code (configuration-cache safe:
// providers.exec is tracked as a build input and evaluated lazily).
val commitCount: Provider<Int> = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
}

// Signing credentials for the release variant. CI passes env vars
// (KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) decoded
// from GitHub secrets; local builds use a sibling keystore.properties file.
// Configuration-cache compatible: file content is read via providers.fileContents
// (tracked build input, evaluated lazily), env vars via providers.environmentVariable.
val keystoreProps: Provider<Properties> = providers.fileContents(
    rootProject.layout.projectDirectory.file("keystore.properties")
).asText.map { txt: String ->
    Properties().apply { load(StringReader(txt)) }
}.orElse(Properties())

val releaseStoreFile: Provider<String> = providers.environmentVariable("KEYSTORE_PATH")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("storeFile") ?: "" })
val releaseStorePassword: Provider<String> = providers.environmentVariable("KEYSTORE_PASSWORD")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("storePassword") ?: "" })
val releaseKeyAlias: Provider<String> = providers.environmentVariable("KEY_ALIAS")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("keyAlias") ?: "" })
val releaseKeyPassword: Provider<String> = providers.environmentVariable("KEY_PASSWORD")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("keyPassword") ?: "" })
val hasSigningCredentials: Provider<Boolean> = releaseStoreFile
    .map { path: String -> path.isNotBlank() }
    .orElse(false)

// ---------------------------------------------------------------------------
// Native EasyTier core (libeasytier_android_jni.so)
//
// Built from the EasyTier-Core git submodule with cargo-ndk into
// app/build/generated/rustJniLibs/<abi>/ and registered as a jniLibs source.
//   ./gradlew :app:buildRustJniLibs                  build only the native libs
//   -PrustAbis=arm64-v8a,x86_64                      limit ABIs (default: all three)
//   -PskipRustBuild -PrustJniLibsDir=/path/to/dir    use a prebuilt <dir>/<abi>/*.so
//   -PrustPatches=a.patch,b.patch                    apply patches/<name> to the core (opt-in)
// ---------------------------------------------------------------------------
val skipRustBuild: Boolean = providers.gradleProperty("skipRustBuild")
    .map { it != "false" }.orElse(false).get()
val prebuiltRustJniLibsDir: Provider<String> = providers.gradleProperty("rustJniLibsDir")
val rustAbis: List<String> = providers.gradleProperty("rustAbis")
    .map { v -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    .orElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64")).get()
val rustPatches: List<String> = providers.gradleProperty("rustPatches")
    .map { v -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    .orElse(emptyList()).get()

val rustCoreDir = rootProject.layout.projectDirectory.dir("EasyTier-Core")
val rustCoreCommit: Provider<String> = providers.exec {
    workingDir = rootProject.projectDir
    commandLine("git", "rev-parse", "HEAD:EasyTier-Core")
}.standardOutput.asText.map { it.trim() }

val buildRustJniLibs = tasks.register<BuildRustJniLibsTask>("buildRustJniLibs") {
    group = "build"
    description = "Builds libeasytier_android_jni.so from EasyTier-Core with cargo-ndk."
    coreDir.set(rustCoreDir)
    coreSources.from(fileTree(rustCoreDir) {
        exclude(
            "target/**", ".git", ".git/**", "**/node_modules/**", "docs/**", "assets/**",
            "easytier-gui/**", "easytier-web/**", "easytier-contrib/easytier-uptime/**",
            "*.md", "*.json", "*.yaml", "*.yml", "script/**", "**/frontend*/**",
        )
    })
    coreCommit.set(rustCoreCommit)
    abis.set(rustAbis)
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    patchNames.set(rustPatches)
    patchFiles.from(fileTree(rootProject.layout.projectDirectory.dir("patches")) { include("*.patch") })
    cargoTargetDir.set(rootProject.layout.buildDirectory.dir("rust-target"))
    outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs"))
    buildInfoFile.set(layout.buildDirectory.file("generated/rustJniLibs-BUILD-INFO.txt"))
}

android {
    namespace = "cc.ptoe.easytier.compose"
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "cc.ptoe.easytier.compose"
        minSdk = 24
        targetSdk = 36
        // Placeholders; real values are wired per-variant via androidComponents
        // below so the git invocation stays configuration-cache compatible.
        versionCode = 1
        versionName = "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    signingConfigs {
        create("release") {
            // Only populate when credentials resolve (env vars on CI, or
            // keystore.properties locally). When absent, the release variant
            // falls back to the debug signing config so builds still succeed.
            if (hasSigningCredentials.get()) {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        debug {
            // Sign debug builds with the release key when credentials are
            // available (env vars on CI, or keystore.properties locally) so
            // debug and release variants share the same signing identity and
            // can be installed interchangeably. Falls back to AGP's default
            // debug signing when credentials are absent.
            if (hasSigningCredentials.get()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // Attach the release signing config when credentials are available;
            // otherwise keep AGP's default (debug) signing so local builds
            // without the keystore still produce an installable APK.
            if (hasSigningCredentials.get()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    androidResources {
        // Languages shipped in the APK (keep in sync with res/xml/locales_config.xml).
        localeFilters += listOf("en", "ru", "zh-rCN")
    }
    dependenciesInfo {
        // Google-signed dependency metadata blob; not reproducible and not wanted in F-Droid builds.
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            // Build one APK per ABI instead of a single fat APK containing all
            // three native libs (~45 MB). AGP forbids combining this with
            // ndk.abiFilters, so the ABI list is declared here explicitly.
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    bundle {
        abi {
            // Keep ABI splits inside the App Bundle so Google Play assembles
            // device-specific APKs on the server side.
            enableSplit = true
        }
    }
}

// Wire versionCode (commit count) and versionName (semantic version) per-variant
// output using lazy providers, so the build is configuration-cache compatible and
// Android Studio / CLI share the same source of truth without extra flags.
androidComponents {
    onVariants { variant ->
        if (skipRustBuild) {
            val dir = prebuiltRustJniLibsDir.orNull
                ?: throw GradleException("-PskipRustBuild requires -PrustJniLibsDir=<dir containing <abi>/libeasytier_android_jni.so>")
            variant.sources.jniLibs?.addStaticSourceDirectory(file(dir).absolutePath)
        } else {
            variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustJniLibs, BuildRustJniLibsTask::outputDir)
        }
        variant.outputs.forEach { output ->
            output.versionCode.set(commitCount)
            output.versionName.set(semanticVersion)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)
    implementation(libs.libsu.core)
    implementation(libs.kotlin.parcelize.runtime)
    implementation(libs.libsu.service)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}