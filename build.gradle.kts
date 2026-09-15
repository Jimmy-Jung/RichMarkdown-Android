// Author: JunyoungJung
// Date: 2026-09-15

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
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
