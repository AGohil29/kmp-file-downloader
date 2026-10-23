plugins {
    id("kmp-library")
    alias(libs.plugins.sqldelight)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

sqldelight {
    databases {
        create("DownloadDatabase") {
            packageName.set("com.arun.downloader.storage.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
            implementation(libs.findLibrary("sqldelight-runtime").get())
            implementation(libs.findLibrary("sqldelight-coroutines").get())
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
            }
        }
        androidMain.dependencies {
            implementation(libs.findLibrary("sqldelight-driver-android").get())
        }
        iosMain.dependencies {
            implementation(libs.findLibrary("sqldelight-driver-native").get())
        }
        // --- Test dependencies ---
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions").get())
            implementation(libs.findLibrary("okio-fakefilesystem").get())
        }

        // JVM/Desktop test gets the JDBC SQLite in-memory driver
        val desktopTest by getting {
            dependencies {
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
            }
        }

        // iOS tests get the Native SQLite driver
        iosTest.dependencies {
            implementation(libs.findLibrary("sqldelight-driver-native").get())
        }
    }
}