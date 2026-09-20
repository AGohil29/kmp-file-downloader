import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<LibraryExtension> {
    namespace = "com.arun.downloader"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extensions.configure<KotlinMultiplatformExtension> {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all")
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Compiler Enforcements
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    allWarningsAsErrors.set(false)
                    freeCompilerArgs.addAll(
                        "-Xexpect-actual-classes"
                    )
                    optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
                }
            }
        }
    }

    sourceSets.apply {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("okio").get())
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions").get())
            implementation(libs.findLibrary("okio-fakefilesystem").get())
        }
        named("desktopTest").dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-swing").get())
        }
    }
}