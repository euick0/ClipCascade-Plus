// Standalone so the protocol layer builds and tests without Node, the Android SDK or a device.
// The app consumes it through includeBuild() in ../settings.gradle.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "clipcascade-core"
