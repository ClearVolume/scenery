
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://raw.githubusercontent.com/kotlin-graphics/mary/master")
    }
}

enableFeaturePreview("VERSION_CATALOGS")

plugins {
    id("sciJava.catalogs") version "30.0.0+66"
}

rootProject.name = "scenery"

gradle.rootProject {
    group = "graphics.scenery"
    version = "0.7.0-beta-8-SNAPSHOT-00"
    description = "flexible scenegraphing and rendering for scientific visualisation"
}