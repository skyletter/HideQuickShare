pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Xposed API 82（jcenter 已关闭，官方仓库在这）
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "HideQuickShare"
include(":app")
