import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "dev.atomic.app.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.atomic.app"
        // minSdk 26 lets us ship a single adaptive-icon asset instead of the
        // legacy PNG mipmap matrix — dropping API 24/25 loses <1% of devices
        // and is standard for new apps as of 2024+.
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures {
        compose = true
    }

    // Shared keystore committed to the repo so local builds and CI produce
    // APKs with the same signature. Without this, Android refuses to install
    // a new build over one signed with a different key — hence the "conflicts
    // with existing package" error users saw when switching between local
    // debug and CI-produced release APKs.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("androidApp/keystore/shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Workaround for CMP-7611 / CMP-9547: register the compose resources directory
// produced by :composeApp as an Android asset source so they are merged into the APK.
// https://youtrack.jetbrains.com/issue/CMP-7611
// https://youtrack.jetbrains.com/issue/CMP-9547
val composeResourcesAssetsDir = rootProject.file(
    "composeApp/build/generated/compose/resourceGenerator/androidMain/assets"
)

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addStaticSourceDirectory(composeResourcesAssetsDir.absolutePath)
    }
}

afterEvaluate {
    val composeResourceTasks = listOf(
        ":composeApp:copyAndroidMainComposeResourcesToAndroidAssets",
        ":composeApp:prepareComposeResourcesTaskForCommonMain",
        ":composeApp:convertXmlValueResourcesForCommonMain",
        ":composeApp:copyNonXmlValueResourcesForCommonMain",
    )
    tasks.matching { it.name.contains("merge") && it.name.contains("Assets") }
        .configureEach { composeResourceTasks.forEach { path -> dependsOn(path) } }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
}
