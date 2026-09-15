// Author: JunyoungJung
// Date: 2026-09-15
//
// 순수 JVM 코어 (DEVELOPMENT.md D2). Android 의존성이 없어 host JVM에서 바로 테스트한다.
// iOS `RichMarkdownCore`(Foundation-only, `swift build --target RichMarkdownCore`)에 대응한다.

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // 코어 공개 심볼은 opt-in 어노테이션으로 표시한다. 모듈 내부에서는 경고 없이 쓴다.
        optIn.add("io.github.jimmyjung.richmarkdown.core.InternalRichMarkdownApi")
    }
}

dependencies {
    // D3: commonmark-java. `List.of` 사용으로 Android API 30 미만은 소비 앱의
    // coreLibraryDesugaring이 필요하다 (D3a, README 참고).
    api(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
