# RichMarkdown (Android)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-minSdk%2024-3DDC84.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-0.1.0%20%EA%B0%9C%EB%B0%9C%20%EC%A4%91-yellow.svg)](CHANGELOG.md)

iOS [RichMarkdown](https://github.com/Jimmy-Jung/RichMarkdown)과 **동일한 렌더 계약**을 제공하는
Android 라이브러리. LLM 채팅 메시지의 Markdown, GFM 표, 인라인/블록 LaTeX 수식, 코드 블록을
WebView 없이 네이티브로 렌더한다. Jetpack Compose는 `RichMarkdown()`, Android View는
`RichMarkdownView`를 쓴다 — 두 렌더러는 같은 파서·수식 raster·캐시를 공유한다.

```
원의 넓이는 \( A = \pi r^2 \)입니다.
            ↓
문장 흐름 안에 baseline 정렬된 수식이 포함된 네이티브 텍스트
```

- 코어는 WebView·JavaScript 런타임·이미지 로더를 링크하지 않는다. 코드 하이라이팅과 Mermaid는
  별도 opt-in 모듈이다.
- 스트리밍 입력(최신 전체 문자열)을 전제로 설계했다. coalescing + latest-wins.
- 수식 문법·실패 정책·스트리밍 표시 규칙은 iOS와 같다. 설계 결정과 근거는
  [DEVELOPMENT.md](DEVELOPMENT.md)에 있다.

## 현재 상태

**골격 단계.** 코어 파이프라인(수식 스캔·길이 보존 mask·commonmark 어댑터·스트리밍 tail·
입력 상한·latest-wins worker)과 iOS에서 이식한 fixture 테스트가 있다. Compose·View 렌더러와
수식·하이라이트·Mermaid 엔진 연동은 P0 spike(수식 엔진 검증) 뒤에 들어간다. Maven Central
**미발행**.

## 설치 (예정)

발행 전이다. 발행 뒤 좌표는 다음과 같다.

```kotlin
dependencies {
    implementation("io.github.jimmy-jung:richmarkdown:<version>")

    // opt-in. 필요한 것만 추가한다.
    implementation("io.github.jimmy-jung:richmarkdown-highlight:<version>") // Prism + QuickJS
    implementation("io.github.jimmy-jung:richmarkdown-mermaid:<version>")   // 공식 Mermaid + WebView
}
```

### API 30 미만을 지원하는 앱의 필수 설정

파서 의존성 commonmark-java 0.30이 `java.util.List.of`(Android API 30+)를 쓴다. `minSdk`가
30 미만인 앱은 **core library desugaring**을 켜야 한다. 켜지 않으면 API 30 미만 기기에서
파싱이 실패하고, 라이브러리는 크래시 대신 Markdown 원문을 그대로 표시한다(fail-open).

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

이 요건은 upstream에 `List.of` 치환 PR이 머지되면 제거한다(DEVELOPMENT.md D3a).

## 지원 matrix

| 항목 | 값 |
|---|---|
| minSdk | 24 (전 모듈 동일. 근거: androidx.webkit 1.17.0 floor) |
| compileSdk | 37 (Compose BOM 2026.09.00의 compose 1.12.1이 37 이상 요구, AAR 메타데이터 검사 실측) |
| 빌드 | AGP 9.4.0, Gradle 9.6.0, Kotlin 2.4.20, JDK 17+ |
| 파서 | commonmark-java 0.30.0 (+ gfm-tables, gfm-strikethrough) |
| 수식 엔진 | RaTeX 0.1.14 (provisional — P0 spike 후 확정) |
| 하이라이트 | Prism 1.30.0 + quickjs-kt 1.0.15 (opt-in) |
| 다이어그램 | Mermaid 11.17.2 + Android WebView (opt-in) |

## iOS 계약 대응

| Android | iOS | 비고 |
|---|---|---|
| 0.1.0 (개발 중) | 0.7.1 | 수식 문법·fail-open·스트리밍 규칙 동일. 수식 폰트는 KaTeX 단일(iOS 12종과 다름) |

공유 자산(Prism 번들 22파일, Mermaid 번들 3파일)은 `scripts/sync-ios-assets.sh`가 iOS 저장소에서
복사하고 iOS `Docs/CODE_BLOCK_EXTENSIONS.md`의 SHA-256 표와 대조한다.

```sh
./scripts/sync-ios-assets.sh            # ../RichMarkdown 기본
./scripts/sync-ios-assets.sh /path/to/RichMarkdown
```

## 빌드

```sh
./gradlew :richmarkdown-core:test                       # 코어 fixture (host JVM)
./gradlew -Prichmarkdown.buildRoot=/path/outside build  # 산출물을 저장소 밖에 둘 때
```

## License

MIT — [LICENSE](LICENSE). 번들·의존 라이브러리 라이선스는
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
