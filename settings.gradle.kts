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
    }
}

rootProject.name = "vr-device-control"

include(":shared-protocol")
include(":shared-domain")
include(":vr-controller")
include(":vr-headset")

// Development tool: simulated headsets for exercising the tablet without Quest hardware.
include(":tools:headset-simulator")
