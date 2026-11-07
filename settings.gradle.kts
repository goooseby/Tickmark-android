 pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // This workstation needs a Central mirror for TLS; hosted CI uses Central directly.
        if (System.getenv("CI") != "true") maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
    }
}
rootProject.name = "Tickmark"
include(":app", ":engine")
