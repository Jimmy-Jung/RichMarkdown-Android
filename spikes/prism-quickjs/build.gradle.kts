// Author: JunyoungJung
// Date: 2026-09-15
//
// P0 spike (DEVELOPMENT.md §7): iOS와 동일한 Prism 1.30.0 번들 + `native-tokenize.js`가
// QuickJS(quickjs-kt JVM 변형)에서 로드·토큰화되는지 host에서 확인한다. 발행하지 않는다.
// Android 변형(`quickjs-kt-android`)은 같은 QuickJS 코어를 쓰므로 JS 호환성 판정은 여기서 끝낸다.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Gradle이 KMP 메타데이터로 jvm 변형(quickjs-kt-jvm, macOS/Linux/Windows 네이티브 포함)을 고른다.
    testImplementation(libs.quickjs.kt)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 번들 위치는 저장소 기준 고정 경로다. 테스트가 상대 경로로 읽는다.
    systemProperty("richmarkdown.prismAssets", rootProject.file("richmarkdown-highlight/src/main/assets/prism").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
