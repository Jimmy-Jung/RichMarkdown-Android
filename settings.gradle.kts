// Author: JunyoungJung
// Date: 2026-09-15

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RichMarkdown-Android"

// DEVELOPMENT.md D1·D2·D8: 공개 아티팩트 4개. 코어는 순수 JVM 모듈이다.
include(":richmarkdown-core")
include(":richmarkdown")
include(":richmarkdown-highlight")
include(":richmarkdown-mermaid")
