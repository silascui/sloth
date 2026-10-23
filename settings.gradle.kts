pluginManagement {
    repositories {
        providers.gradleProperty("androidRepositoryMirror").orNull?.let { maven(it) }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("androidRepositoryMirror").orNull?.let { maven(it) }
        providers.gradleProperty("mavenRepositoryMirror").orNull?.let { maven(it) }
        google(); mavenCentral()
    }
}
rootProject.name = "sloth"
include(":player", ":player-ui", ":sample")
