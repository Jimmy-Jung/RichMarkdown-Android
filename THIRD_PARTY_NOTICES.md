# 서드파티 고지

- 작성자: JunyoungJung
- 작성일: 2026-09-15 (KST)

라이선스는 2026-09-15에 각 저장소의 LICENSE 파일 또는 GitHub 라이선스 메타데이터로 확인했다.

| 구성요소 | 버전 | 라이선스 | 포함 방식 | 출처 |
|---|---|---|---|---|
| commonmark-java (`commonmark`, `commonmark-ext-gfm-tables`, `commonmark-ext-gfm-strikethrough`) | 0.30.0 | BSD-2-Clause | Maven 의존성 (`richmarkdown-core`) | https://github.com/commonmark/commonmark-java |
| RaTeX (`ratex-android`) | 0.1.14 | MIT | Maven 의존성 (`richmarkdown`). AAR에 KaTeX 폰트 19면 포함 | https://github.com/erweixin/RaTeX |
| KaTeX Font Software (RaTeX AAR 내 `KaTeX_*.ttf`) | KaTeX 폰트 | SIL Open Font License 1.1 | RaTeX AAR 전이 포함 (`licenses/KaTeX-fonts-NOTICE.txt`) | https://github.com/KaTeX/KaTeX |
| quickjs-kt | 1.0.15 | Apache-2.0 | Maven 의존성 (`richmarkdown-highlight`) | https://github.com/dokar3/quickjs-kt |
| QuickJS (quickjs-kt가 번들하는 엔진) | quickjs-kt 동봉판 | MIT (Fabrice Bellard, Charlie Gordon) | quickjs-kt AAR 전이 포함 | https://github.com/bellard/quickjs |
| Prism | 1.30.0 | MIT | `richmarkdown-highlight/src/main/assets/prism/` 번들 복사 (원문 `PRISM-LICENSE.txt` 동봉) | https://github.com/PrismJS/prism |
| Mermaid | 11.17.2 | MIT | `richmarkdown-mermaid/src/main/assets/mermaid/mermaid.bundle.js` 번들 복사. 번들에 포함된 64개 패키지 고지는 같은 폴더의 `MERMAID-THIRD-PARTY-NOTICES.txt` | https://github.com/mermaid-js/mermaid |
| AndroidX (Compose, core-ktx, webkit, activity) | `gradle/libs.versions.toml` 참조 | Apache-2.0 | Maven 의존성 | https://github.com/androidx/androidx |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | Maven 의존성 | https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp | 5.5.0 | Apache-2.0 | Maven 의존성 (`demo`만 — SSE 클라이언트. 라이브러리 모듈은 의존하지 않음) | https://github.com/square/okhttp |
| Kotlin 표준 라이브러리 | 2.4.20 | Apache-2.0 | Maven 의존성 | https://github.com/JetBrains/kotlin |
| JUnit Jupiter | 6.1.3 | 미확인 (테스트 전용, 배포 아티팩트에 포함되지 않음) | 테스트 의존성 | https://github.com/junit-team/junit-framework |

`native-tokenize.js`(Prism 브리지)와 Mermaid `index.html`은 iOS RichMarkdown 저장소가 작성한
파일이며 이 저장소와 같은 MIT 라이선스다.
