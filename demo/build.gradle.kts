// Author: JunyoungJung
// Date: 2026-09-15
//
// 데모 앱 (iOS Examples/RichMarkdownDemo 대응). 미발행. Compose 채팅·View(RecyclerView) 채팅·
// SSE 스트리밍·샘플 쇼케이스 화면으로 라이브러리 3모듈을 실제 앱 형태로 검증한다.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jimmyjung.richmarkdown.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.jimmyjung.richmarkdown.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // D3a (필수): commonmark의 `List.of`(API 30)를 API 24~29에서 desugar_jdk_libs로 제공한다.
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(project(":richmarkdown"))
    implementation(project(":richmarkdown-highlight"))
    implementation(project(":richmarkdown-mermaid"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // SSE 데모: text/event-stream 바이트 스트림 읽기.
    implementation(libs.okhttp)

    // SSE 프레임 디코더 단위 테스트 (JVM, JUnit4).
    testImplementation("junit:junit:4.13.2")
}
