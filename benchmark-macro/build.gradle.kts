// Author: JunyoungJung
// Date: 2026-10-14
//
// P2 Macrobenchmark (androidx.benchmark.macro). `:demo`의 benchmark 빌드 타입(release + 디버그 서명 + profileable)을
// 실기기에서 콜드·웜 런치, 챗 스크롤(View·Compose), SSE 자동 재생으로 잰다. 미발행.
// 결과는 JSON + 반복마다 perfetto 트레이스. 실행: scripts/run-benchmarks.sh macro.

plugins {
    // AGP는 루트 plugins 블록(apply false)으로 이미 classpath에 있어 버전을 다시 적으면 Gradle이 거부한다(실측). id만 적용한다.
    id("com.android.test")
}

android {
    namespace = "io.github.jimmyjung.richmarkdown.benchmark.macro"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // 대상 앱의 benchmark 빌드 타입과 이름을 맞춘다. 테스트 APK 자체는 디버그 가능해도 측정에 영향이 없다(공식 템플릿).
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":demo"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
