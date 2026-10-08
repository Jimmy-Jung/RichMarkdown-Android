// Author: JunyoungJung
// Date: 2026-10-08
//
// opt-in 블록 편집기 (DEVELOPMENT.md D1a). iOS `RichMarkdownBlockEditor` 대응.
// 순수 Kotlin 모델·코덱(BlockEditorModel·InlineMarkdownCodec)과 EditText 편집 뷰·스타일러·Compose 래퍼를 담는다.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.jimmyjung.richmarkdown.editor"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
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
        // JVM 단위 테스트는 android.jar stub을 기본값 반환으로 둔다 (richmarkdown 모듈과 동일).
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        optIn.add("io.github.jimmyjung.richmarkdown.core.InternalRichMarkdownApi")
    }
}

dependencies {
    api(project(":richmarkdown"))

    // 수식 raster 요청 수명(뷰 attach 동안). richmarkdown은 implementation으로만 가져 컴파일 classpath에 없다.
    implementation(libs.kotlinx.coroutines.android)

    // Compose 편집기 래퍼(AndroidView). kotlin.compose 플러그인은 compile classpath에 Compose runtime이 있어야 한다.
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)

    // JVM 단위 테스트 (모델·코덱 순수 로직). Android 모듈은 JUnit4다.
    testImplementation("junit:junit:4.13.2")

    // 편집 뷰·스타일러 instrumented 테스트. 에뮬레이터에서 connectedDebugAndroidTest로 실행한다 (richmarkdown과 같은 구성).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    // ui-test-junit4가 끌어오는 구 Espresso는 API 37에서 InputManager.getInstance 리플렉션으로 실패한다(실측). richmarkdown과 같은 버전으로 올린다.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Maven Central 발행 (D8). 좌표 io.github.jimmy-jung:<POM_ARTIFACT_ID>:<VERSION_NAME>.
// 실제 발행은 `./gradlew publishToMavenCentral -PRELEASE_SIGNING_ENABLED=true` — 자격 증명·서명 키는 로컬 ~/.gradle/gradle.properties.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("RELEASE_SIGNING_ENABLED").orNull == "true") signAllPublications()
}
