import org.gradle.kotlin.dsl.registering
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.provideDelegate

plugins {
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

// Generate multiplatform Javadoc Jar required by Maven Central rules
val dokkaJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}

// Ensure the LICENSE file is included in META-INF for all published JAR artifacts
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

fun resolveSecret(name: String): String? {
    return (findProperty(name) as? String)
        ?: (findProperty(name.lowercase()) as? String)
        ?: System.getenv(name)
        ?: System.getenv(name.uppercase())
}

val resolvedUsername = resolveSecret("sonatypeUsername") ?: ""
val resolvedPassword = resolveSecret("sonatypePassword") ?: ""
val resolvedSigningKey = resolveSecret("signingKey")
val resolvedSigningPassword = resolveSecret("signingPassword")

// Published artifact base name: downloader-runtime -> kmp-file-downloader,
// downloader-core -> kmp-file-downloader-core, etc.
val publishedBaseName = when (val n = project.name) {
    "downloader-runtime" -> "kmp-file-downloader"
    else -> "kmp-file-downloader-" + n.removePrefix("downloader-")
}

publishing {
    // Publish to Sonatype Central Portal
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

    // Configure POM metadata across all targets (Desktop, Android, iOS Native)
    publications.withType<MavenPublication>().configureEach {
        // Replaces only the project-name prefix, keeping target suffixes (-desktop, -android, -iosarm64...)
        artifactId = artifactId.replaceFirst(project.name, publishedBaseName)
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

// Cryptographic Signing via GPG/PGP
signing {
    if (!resolvedSigningKey.isNullOrBlank()) {
        useInMemoryPgpKeys(resolvedSigningKey, resolvedSigningPassword)
        sign(publishing.publications)
    }
}

// Shared javadoc jar is signed by every Sign task; publish tasks must depend on signing
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}