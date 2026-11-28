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
        val voiceTimingRepository = providers.gradleProperty("ts3jVoiceTimingRepository").orNull
        val sourceRepository = providers.gradleProperty("ts3jSourceRepository").orNull
        require(voiceTimingRepository == null || sourceRepository == null) { "Select only one local TS3j repository" }
        (voiceTimingRepository ?: sourceRepository)?.let { directory ->
            val localRepository = file(directory)
            require(localRepository.isDirectory) { "Build the selected local TS3j repository first" }
            exclusiveContent {
                forRepository { maven { url = uri(localRepository) } }
                filter { includeModule("com.github.monet4070", "ts3j") }
            }
        }
        google()
        mavenCentral()
        if (sourceRepository == null && voiceTimingRepository == null) maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Ts3Mobile"
include(":app")
include(":ts3-protocol")
include(":audio-opus")

