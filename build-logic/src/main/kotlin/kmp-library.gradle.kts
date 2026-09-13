import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<KotlinMultiplatformExtension> {
    // Desktop / JVM Target
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all")
        }
    }

    // iOS Targets
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