import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.maven.publish.base) apply false
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish.base") {
        val artifactName = when (project.name) {
            "player" -> "sloth"
            "player-ui" -> "sloth-ui"
            else -> error("No publication configured for $path")
        }
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(project.group.toString(), artifactName, project.version.toString())
            // Local bundles remain usable without Central credentials or signing keys.
            if (providers.gradleProperty("centralPublishing").orNull == "true") {
                publishToMavenCentral(automaticRelease = false)
                signAllPublications()
            }
            pom {
                url.set("https://github.com/silascui/sloth")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("silascui")
                        name.set("silascui")
                        url.set("https://github.com/silascui")
                    }
                }
                scm {
                    url.set("https://github.com/silascui/sloth")
                    connection.set("scm:git:https://github.com/silascui/sloth.git")
                    developerConnection.set("scm:git:ssh://git@github.com/silascui/sloth.git")
                }
            }
        }
        // Ship the maintained architecture and integration guides as the documentation jar.
        val documentationJar = tasks.register<Jar>("documentationJar") {
            archiveClassifier.set("javadoc")
            from(rootProject.file("docs")) { include("*.md") }
            from(rootProject.files("README.md", "LICENSE", "NOTICE.md"))
        }
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                artifact(documentationJar)
            }
        }
    }
}
