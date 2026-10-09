plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.compose.experimental.desktop.ui.tooling.preview.DesktopPreview")
            }
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":downloader-core"))
                implementation(project(":downloader-filesystem"))
                implementation(project(":downloader-storage"))
                implementation(project(":downloader-network"))
                implementation(project(":downloader-runtime"))
                implementation(libs.findLibrary("okio").get())
                implementation(compose.desktop.currentOs)
                implementation(libs.findLibrary("kotlinx-coroutines-swing").get())
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
                implementation(libs.findLibrary("ktor-client-cio").get())
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.arun.downloader.sample.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "KmpFileDownloaderSample"
            packageVersion = "1.0.0"
        }
    }
}