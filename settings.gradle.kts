pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo.eclipse.org/content/repositories/paho-releases/") }
        // fleet (2026-11-21, review b6-12): the jitpack libraries (Sendspin client, RootEncoder) are kept in the fork
        // (libs-local/, jar/aar + jitpack's own pom/module) so an urgent fix never waits on jitpack.io; checksums of
        // every dependency are pinned in gradle/verification-metadata.xml (a changed file is refused).
        maven {
            url = uri(settingsDir.resolve("libs-local"))
            content { includeGroupByRegex("com\\.github\\..*") }
        }
        maven { url = uri("https://jitpack.io") }  // RootEncoder / RTSP-Server
    }
}

rootProject.name = "portal-ha-bridge"
include(":app")
