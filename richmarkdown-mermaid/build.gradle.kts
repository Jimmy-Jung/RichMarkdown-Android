// Author: JunyoungJung
// Date: 2026-09-15
//
// opt-in Mermaid 다이어그램 (DEVELOPMENT.md D7). iOS와 같은 공식 Mermaid 11.17.2 번들과
// index.html을 Android WebView에서 실행한다. 미사용 앱은 이 모듈을 링크하지 않는다.

plugins {
    alias(libs.plugins.android.library)
    // RichMarkdownDiagramRendering.Content가 @Composable이다 (AndroidView 래핑).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.jimmyjung.richmarkdown.mermaid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 계측 테스트 커버리지(JaCoCo, createDebugAndroidTestCoverageReport). JVM 단위 테스트는 Kover가 집계하므로
            // enableUnitTestCoverage는 켜지 않는다(두 에이전트가 같은 테스트를 이중 계측하지 않게).
            enableAndroidTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// 공유 fixture(iOS 코퍼스 동기화본, richmarkdown-core 소유·읽기 전용)를 androidTest assets로 노출한다.
// AGP 9에서는 legacy `android.sourceSets[...]` 접근이 configuration을 깨므로 Variant Sources API를 쓴다.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(
            rootDir.resolve("richmarkdown-core/src/test/resources").path,
        )
    }
}

dependencies {
    api(project(":richmarkdown"))
    // WebViewAssetLoader: assets를 https 오리진으로 서빙해 index.html의 CSP `'self'`를 유지한다.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)

    // instrumented 테스트: P0 spike(MermaidWebViewSpikeTest) + MermaidDiagramViewTest. 에뮬레이터에서 connectedDebugAndroidTest로 실행한다.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
}

// Maven Central 발행 (D8). 좌표 io.github.jimmy-jung:<POM_ARTIFACT_ID>:<VERSION_NAME>.
// 실제 발행은 `./gradlew publishToMavenCentral -PRELEASE_SIGNING_ENABLED=true` — 자격 증명·서명 키는 로컬 ~/.gradle/gradle.properties.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("RELEASE_SIGNING_ENABLED").orNull == "true") signAllPublications()
}
