plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.git.versioning)
}

version = "0.0.0-SNAPSHOT"
gitVersioning.apply {
    refs {
        tag("(?<version>.+)") {
            version = "\${ref.version}"
        }
        branch(".+") {
            version = "\${ref.slug}-\${commit.short}-SNAPSHOT"
        }
    }
    rev {
        version = "\${commit}"
    }
}
