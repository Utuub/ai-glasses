import java.util.Properties
import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

// Secrets live in local.properties (git-ignored). Missing keys become "" so the app shows a clear error.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String) = "\"" + (localProps.getProperty(name) ?: "") + "\""

android {
    namespace = "com.example.vuzixem"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vuzixem"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLAUDE_KEY", secret("CLAUDE_KEY"))
        buildConfigField("String", "SLACK_WEBHOOK", secret("SLACK_WEBHOOK"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Offline voice (build guide Part 1)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
// Vosk speech model (~40 MB, Apache 2.0). Not committed: downloaded into assets on the first build.
// Vosk's StorageService needs a "uuid" file in the model folder to know when to re-unpack it.
val voskModelDir = layout.projectDirectory.dir("src/main/assets/model-en-us").asFile
val downloadVoskModel by tasks.registering {
    val dir = voskModelDir
    outputs.dir(dir)
    onlyIf { !File(dir, "uuid").exists() }
    doLast {
        val name = "vosk-model-small-en-us-0.15"
        val zip = File(temporaryDir, "$name.zip")
        zip.writeBytes(URI("https://alphacephei.com/vosk/models/$name.zip").toURL().readBytes())
        dir.deleteRecursively()
        val z = ZipFile(zip)
        try {
            for (e in z.entries().asSequence()) {
                val rel = e.name.removePrefix("$name/")
                if (rel.isEmpty() || e.isDirectory) continue
                val out = File(dir, rel)
                out.parentFile.mkdirs()
                val input = z.getInputStream(e)
                try { out.writeBytes(input.readBytes()) } finally { input.close() }
            }
        } finally { z.close() }
        File(dir, "uuid").writeText(name)
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }
