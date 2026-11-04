pluginManagement {
    repositories {
        // 本机只能访问腾讯云镜像，故镜像放在最前；
        // 如果你的网络能直连，可把这两行删掉，仅保留 google() / mavenCentral()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/google-maven/")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/google-maven/")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")

        google()
        mavenCentral()
    }
}

rootProject.name = "TtsApp"
include(":app")
