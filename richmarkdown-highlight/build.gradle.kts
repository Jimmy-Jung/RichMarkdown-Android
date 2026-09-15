// Author: JunyoungJung
// Date: 2026-09-15
//
// opt-in 코드 블록 하이라이팅 (DEVELOPMENT.md D6). iOS와 동일한 Prism 1.30.0 번들과
// `native-tokenize.js` 브리지를 QuickJS에서 실행한다. 미사용 앱은 이 모듈을 링크하지 않는다.

plugins {
    alias(libs.plugins.android.library)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.jimmyjung.richmarkdown.highlight"
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
}

dependencies {
    api(project(":richmarkdown"))
    // QuickJS 바인딩. 호출은 한 파일에 가둬 교체 가능하게 둔다 (D6).
    implementation(libs.quickjs.kt)
    implementation(libs.kotlinx.coroutines.android)

    // P0 spike instrumented 테스트 (에뮬레이터에서 so 로드·토큰화 실측)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
}
