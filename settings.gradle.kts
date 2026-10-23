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
        // Xposed / LSPosed 官方 API 仓库（仅 compileOnly，不会打进 APK）
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "VpnAutoSwitch"
include(":app")
