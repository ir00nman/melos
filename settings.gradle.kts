rootProject.name = "Melos"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { url = java.net.URI.create("https://jitpack.io") }   // murglar-plugins core
    }
}

include("app")
include("android")
