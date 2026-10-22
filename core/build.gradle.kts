import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin domain layer: models, FTM format, engines, importers, sync logic.
// No Compose, no Room — keeps tests fast (`:core:jvmTest`) and the domain portable.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "io.github.vargfor.ftmnotes.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
            api(libs.okio) // zip read/write, hashing
            implementation(libs.ksoup) // HTML → Markdown for importers
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}
