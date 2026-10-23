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
        if (project.name == "downloader-runtime") {
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

// Cryptographic Signing via GPG/PGP
signing {
    if (!resolvedSigningKey.isNullOrBlank()) {
        useInMemoryPgpKeys(resolvedSigningKey, resolvedSigningPassword)
        sign(publishing.publications)
    }
}

// Avoid task race conditions during publication
tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}