plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Runtime pieces (PRoot, rootfs, web UI) are produced by scripts under
// runtime/ and webui/ (see tools/build-apk.sh) into this directory.
val generated = layout.buildDirectory.dir("generated/poordsh")


android {
    namespace = "dev.mokouliszt.poordsh"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mokouliszt.poordsh"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "0.17.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // Supplied by the person building a release; never committed.
            val store = System.getenv("POORDSH_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("POORDSH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POORDSH_KEY_ALIAS")
                keyPassword = System.getenv("POORDSH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("POORDSH_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    sourceSets["main"].apply {
        jniLibs.srcDir(generated.map { it.dir("jniLibs") })
        assets.srcDir(generated.map { it.dir("assets") })
    }

    // PRoot must exist as a real file in nativeLibraryDir to be exec()'d.
    packaging { jniLibs { useLegacyPackaging = true } }
    // The rootfs archive is already xz-compressed.
    androidResources { noCompress += listOf("xz") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.tukaani:xz:1.10")
    // SKILL.md frontmatter (YAML), checked like Anthropic's skill-creator does.
    implementation("org.snakeyaml:snakeyaml-engine:2.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
}

// Fail early with a clear message instead of shipping an APK without a runtime.
val checkRuntimeInputs by tasks.registering {
    val root = generated
    doLast {
        val dir = root.get().asFile
        val required = listOf(
            "jniLibs/arm64-v8a/libproot.so",
            "jniLibs/arm64-v8a/libproot-loader.so",
            "assets/runtime/rootfs.tar.xz",
            "assets/runtime/rootfs.json",
            "assets/webui/index.html",
        )
        val missing = required.filterNot { dir.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing runtime inputs (run tools/build-apk.sh): ${missing.joinToString()}",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkRuntimeInputs) }
