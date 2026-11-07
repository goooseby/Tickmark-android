 pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven { url = uri("https://maven.aliyun.com/repository/central") }; mavenCentral() }
}
rootProject.name = "Tickmark"
include(":app", ":engine")
