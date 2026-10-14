// Author: JunyoungJung
// Date: 2026-09-15

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover)
}

// 라이브러리 5개 모듈의 JVM 단위 테스트 커버리지를 합산한다(`koverXmlReport`·`koverHtmlReport`·`koverVerify`).
// 계측 테스트(androidTest)는 Kover가 집계하지 않는다 — Compose·View·WebView 경로의 검증은 계측 테스트 결과로 본다.
dependencies {
    kover(project(":richmarkdown-core"))
    kover(project(":richmarkdown"))
    kover(project(":richmarkdown-highlight"))
    kover(project(":richmarkdown-mermaid"))
    kover(project(":richmarkdown-editor"))
}

// 빌드 산출물을 저장소 밖으로 보낸다.
//   ./gradlew -Prichmarkdown.buildRoot=/path/outside build
// 지정하지 않으면 Gradle 기본값(각 모듈의 build/)이다.
val buildRoot = providers.gradleProperty("richmarkdown.buildRoot").orNull
if (buildRoot != null) {
    allprojects {
        val key = if (project.path == ":") "_root" else project.path.removePrefix(":").replace(':', '_')
        layout.buildDirectory.set(File(buildRoot, key))
    }
}
