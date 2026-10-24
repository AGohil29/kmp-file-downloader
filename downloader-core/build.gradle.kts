plugins {
    id("kmp-library")
    id("kmp-publishing")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("ktor-client-core").get())
        }
    }
}
