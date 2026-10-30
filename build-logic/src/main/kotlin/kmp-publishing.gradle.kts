import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

/**
 * Publishing convention for all library modules, targeting Maven Central via the Central Portal.
 *
 * Credentials / signing are read from Gradle properties (or ORG_GRADLE_PROJECT_* env vars on CI):
 *   mavenCentralUsername, mavenCentralPassword      -> Central Portal *user token* (not your login)
 *   signingInMemoryKey, signingInMemoryKeyPassword  -> ASCII-armored secret key (+ optional passphrase)
 *   signingInMemoryKeyId                            -> optional, key id
 */
plugins {
    id("com.vanniktech.maven.publish")
}

// Published artifact base name: downloader-runtime -> kmp-file-downloader,
// downloader-core -> kmp-file-downloader-core, etc. The plugin appends target suffixes itself
// (-desktop, -android, -iosarm64, ...).
val publishedBaseName = when (val n = project.name) {
    "downloader-runtime" -> "kmp-file-downloader"
    else -> "kmp-file-downloader-" + n.removePrefix("downloader-")
}

// Ship the LICENSE inside META-INF of every published jar.
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

val hasSigningKey: Boolean =
    providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true

mavenPublishing {
    publishToMavenCentral()        // Central Portal. Release is reviewed/published in the portal UI.
    if (hasSigningKey) {
        signAllPublications()      // Required by Central; skipped locally so publishToMavenLocal works unsigned.
    }

    coordinates(
        groupId = project.group.toString(),
        artifactId = publishedBaseName,
        version = project.version.toString()
    )

    // Central requires a javadoc jar; an empty one is accepted. Switch to
    // JavadocJar.Dokka("dokkaHtml") later if you want real API docs in it.
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            androidVariantsToPublish = listOf("release")
        )
    )

    pom {
        name.set("KMP File Downloader")
        description.set("A resilient, production-grade Kotlin Multiplatform file downloader supporting Android, iOS, and Desktop.")
        inceptionYear.set("2026")
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

// Fail loudly instead of uploading unsigned artifacts that Central would reject anyway.
val requireSigningKeyForRelease = tasks.register("requireSigningKeyForRelease") {
    val present = hasSigningKey
    doLast {
        check(present) {
            "Refusing to publish to Maven Central without a signing key. " +
                "Set ORG_GRADLE_PROJECT_signingInMemoryKey (and ...KeyPassword / ...KeyId if needed)."
        }
    }
}
tasks.matching { it.name.contains("MavenCentral") }.configureEach {
    dependsOn(requireSigningKeyForRelease)
}
