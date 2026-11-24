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
        // chesslib (move generation, FEN, SAN): the Android stand-in for chesskit-swift.
        maven("https://jitpack.io") { content { includeGroup("com.github.bhlangonijr") } }
    }
}
rootProject.name = "ChessAI"
include(":app")
