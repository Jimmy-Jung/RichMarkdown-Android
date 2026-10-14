// Author: JunyoungJung
// Date: 2026-09-15
//
// 순수 JVM 코어 (DEVELOPMENT.md D2). Android 의존성이 없어 host JVM에서 바로 테스트한다.
// iOS `RichMarkdownCore`(Foundation-only, `swift build --target RichMarkdownCore`)에 대응한다.

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
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
    // 공용 fixture 러너·퍼즈(iOS Tests/Fixtures/README.md §4·§6)의 환경 변수를 테스트 JVM에 전달한다.
    // 데몬의 환경이 오래됐을 수 있으니 이 변수를 쓰는 실행은 --no-daemon으로 돌린다.
    // Test 작업은 environment를 입력으로 추적하지 않으므로 입력 속성으로도 등록한다(퍼즈 횟수만 바꿔도 다시 실행).
    listOf("RICHMARKDOWN_FIXTURE_OUT", "RICHMARKDOWN_FUZZ_ITERATIONS", "RICHMARKDOWN_FUZZ_SEED", "RICHMARKDOWN_FUZZ_DUMP")
        .forEach { name ->
            providers.environmentVariable(name).orNull?.let {
                environment(name, it)
                inputs.property(name, it)
            }
        }
}

// Maven Central 발행 (D8). 좌표 io.github.jimmy-jung:<POM_ARTIFACT_ID>:<VERSION_NAME>.
// 실제 발행은 `./gradlew publishToMavenCentral -PRELEASE_SIGNING_ENABLED=true` — 자격 증명·서명 키는 로컬 ~/.gradle/gradle.properties.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("RELEASE_SIGNING_ENABLED").orNull == "true") signAllPublications()
}

// CI 커버리지 기준(`./gradlew koverVerify`). Core는 JVM 단위 테스트로 거의 전부 실행되므로 90%를 하한으로 둔다.
// 표시·하이라이트·Mermaid·편집기 화면 경로는 계측 테스트(수동 job)의 JaCoCo 보고서로 본다.
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
