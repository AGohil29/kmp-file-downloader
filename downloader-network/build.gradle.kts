plugins {
    id("kmp-library")
    id("publishing")
    `maven-publish`
    id("signing")
    id("org.jetbrains.dokka")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
            implementation(libs.findLibrary("ktor-client-core").get())
        }

        androidMain.dependencies {
            implementation(libs.findLibrary("ktor-client-okhttp").get())
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.findLibrary("ktor-client-cio").get())
            }
        }

        iosMain.dependencies {
            implementation(libs.findLibrary("ktor-client-darwin").get())
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("ktor-client-mock").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
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

val dokkaJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}

fun resolveSecret(name: String): String? {
    return (findProperty(name) as? String)
        ?: (findProperty(name.lowercase()) as? String)
        ?: System.getenv(name)
        ?: System.getenv(name.uppercase())
}

val resolvedSigningKey = resolveSecret("signingKey")
val resolvedSigningPassword = resolveSecret("signingPassword")
val resolvedUsername = resolveSecret("sonatypeUsername") ?: ""
val resolvedPassword = resolveSecret("sonatypePassword") ?: ""

publishing {
    repositories {
        maven {
            name = "Sonatype"
            val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

            credentials {
                username = resolvedUsername
                password = resolvedPassword
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        if (artifactId.startsWith("downloader-runtime")) {
            artifactId = artifactId.replace("downloader-runtime", "kmp-file-downloader")
        }

        artifact(dokkaJavadocJar)

        pom {
            name.set("KMP File Downloader")
            description.set("A resilient, production-grade Kotlin Multiplatform file downloader supporting Android, iOS, and Desktop.")
            url.set("https://github.com/AGohil29/kmp-file-downloader")

            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("AGohil29")
                    name.set("Arun Gohil")
                    url.set("https://github.com/AGohil29")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/AGohil29/kmp-file-downloader.git")
                developerConnection.set("scm:git:ssh://github.com/AGohil29/kmp-file-downloader.git")
                url.set("https://github.com/AGohil29/kmp-file-downloader")
            }
        }
    }
}

signing {
    if (!resolvedSigningKey.isNullOrBlank()) {
        useInMemoryPgpKeys(resolvedSigningKey, resolvedSigningPassword)
        sign(publishing.publications)
    }
}

// Shared javadoc jar is signed by every Sign task; order publish tasks after signing
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}