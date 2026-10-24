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
        maven { url = java.net.URI.create("https://jogamp.org/deployment/maven") }   // gluegen/jogl для JCEF (окно входа на Chromium)
    }
}

include("app")
