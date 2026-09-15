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

// P0 spike 검증 모듈 — 미발행. host JVM에서 QuickJS + Prism 번들 동작을 확인한다 (DEVELOPMENT.md §7).
include(":spikes:prism-quickjs")
