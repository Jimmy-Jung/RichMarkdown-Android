// Author: JunyoungJung
// Date: 2026-09-15
//
// 렌더 라이브러리 (DEVELOPMENT.md D5). Compose 렌더러와 네이티브 View 렌더러가
// 코어·렌더 모델·수식 raster·캐시를 공유한다. 코어는 WebView·JS 런타임을 링크하지 않는다.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.jimmyjung.richmarkdown"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // D9: androidx.webkit 1.17.0의 floor. 전 모듈 동일.
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // JVM 단위 테스트는 android.jar stub을 기본값 반환으로 둔다 (Looper·Log 등 미호출 경로 보호).
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        optIn.add("io.github.jimmyjung.richmarkdown.core.InternalRichMarkdownApi")
    }
}

dependencies {
    api(project(":richmarkdown-core"))

    // D4 (provisional): RaTeX. 호출은 MathRenderService 한 파일에 가둔다.
    implementation(libs.ratex.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)

    // JVM 단위 테스트 (렌더 모델 순수 로직). Android 모듈은 JUnit4다.
    testImplementation(libs.kotlin.test)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)

    // P0 spike instrumented 테스트 (RaTeXSpikeTest). 에뮬레이터에서 connectedDebugAndroidTest로 실행한다.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
}
