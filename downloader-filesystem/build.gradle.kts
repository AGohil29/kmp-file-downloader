plugins {
    id("kmp-library")
    id("kmp-publishing")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
            implementation(libs.findLibrary("okio").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("okio-fakefilesystem").get())
            implementation(libs.findLibrary("kotest-assertions").get())
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
