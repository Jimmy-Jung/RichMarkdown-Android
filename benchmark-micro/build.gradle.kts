// Author: JunyoungJung
// Date: 2026-10-14
//
// P2 Microbenchmark (androidx.benchmark). 라이브러리 4모듈의 핫 패스(코어 파싱·수식 스캔·RaTeX·Prism·편집기 코덱)를
// 실기기에서 `BenchmarkRule.measureRepeated`로 잰다. 미발행. 측정은 release 테스트 APK(디버그 불가)로만 한다.
// 실행: scripts/run-benchmarks.sh micro (외장 SSD 규칙·결과 수집 포함).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "io.github.jimmyjung.richmarkdown.benchmark.micro"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 디버그 빌드는 JIT·검증이 달라 수치가 왜곡된다. 테스트는 release로만 돈다.
    testBuildType = "release"
    buildTypes {
        release {
            isDefault = true
        }
    }
}

// 파싱 입력은 코어 fixture 샘플을 복사 없이 그대로 테스트 APK assets로 묶는다 (iOS Tests/Fixtures와 동일 원본).
// AGP 9 새 DSL에서는 `android.sourceSets`가 legacy 타입으로 캐스트돼 실패하므로(실측) Variant Sources API를 쓴다.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets
            ?.addStaticSourceDirectory(rootProject.file("richmarkdown-core/src/test/resources/fixtures/samples").absolutePath)
    }
}

kotlin {
    compilerOptions {
        optIn.add("io.github.jimmyjung.richmarkdown.core.InternalRichMarkdownApi")
    }
}

dependencies {
    androidTestImplementation(project(":richmarkdown-core"))
    androidTestImplementation(project(":richmarkdown"))
    androidTestImplementation(project(":richmarkdown-highlight"))
    androidTestImplementation(project(":richmarkdown-editor"))
    // RaTeX parse/draw는 MathRenderService가 호출하는 io.ratex API를 그대로 잰다 (서비스 캐시가 반복 측정을 가린다).
    androidTestImplementation(libs.ratex.android)
    androidTestImplementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("junit:junit:4.13.2")
}
