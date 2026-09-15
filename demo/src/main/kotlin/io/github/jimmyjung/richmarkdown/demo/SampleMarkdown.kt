// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.content.Context
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.highlight.PrismHighlighter
import io.github.jimmyjung.richmarkdown.mermaid.MermaidDiagramRenderer
import java.text.BreakIterator

/** 채팅 화면 메시지. iOS `ChatDemo.swift`의 `ChatMessage` 대응. */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    /** 이 답변이 확인하려는 케이스 이름. 라벨 칩으로 보여준다. */
    val caseName: String = "",
) {
    enum class Role { User, Assistant }
}

/** 쇼케이스 한 구획: 제목 + 원문. */
data class SampleSection(val title: String, val markdown: String)

/**
 * iOS 데모 fixture 포팅. 출처는 각 항목 주석에 적었다.
 *
 * Kotlin raw string은 `$식별자`를 템플릿으로 해석하므로 달러 수식 샘플의 `$a`·`$x`는 `${'$'}`로 적었다.
 */
object SampleMarkdown {

    /** iOS `ChatDemo.swift` — "Dollar math (opt-in) · 통화 표기" 답변. `$` 토글 비교용. */
    val dollarMath: String = """
        opt-in이 꺼져 있으면 아래는 모두 그냥 텍스트입니다. `$` 수식 토글을 켜서 비교하세요.

        인라인: ${'$'}a + b$ 그리고 ${'$'}x^2 + y^2 = z^2$

        블록:

        $$ \sum_{i=1}^{n} i = \frac{n(n+1)}{2} $$

        통화 표기는 토글과 무관하게 수식이 아니어야 합니다:
        가격은 $5 이고 범위는 $5 and $10 입니다. ${'$'}x$5 도 수식이 아닙니다.
        이스케이프한 \$100 도 그대로 보입니다.
    """.trimIndent()

    /** iOS `ChatDemo.swift` `ChatFixtures.conversation` (질문/답변 쌍). */
    val conversation: List<ChatMessage> = buildList {
        var nextId = 0L
        fun question(text: String) = add(ChatMessage(nextId++, ChatMessage.Role.User, text))
        fun answer(caseName: String, text: String) =
            add(ChatMessage(nextId++, ChatMessage.Role.Assistant, text.trimIndent(), caseName))

        question("원의 넓이 공식이 뭐야?")
        answer(
            "인라인 수식 · baseline",
            """
            원의 넓이는 \( A = \pi r^2 \)입니다. 반지름이 \(r = 3\)이면
            \( A = 9\pi \approx 28.27 \)이 됩니다.

            한글 사이에 \(\sqrt{x^2+1}\) 근호, \(x_{i}^{2}\) 첨자, \(\frac{a}{b}\) 분수를
            섞어도 baseline이 맞아야 합니다. 이모지 🙂 옆의 \(\alpha + \beta\)도 확인하세요.
            """,
        )

        question("가우스 적분 알려줘")
        answer("블록 수식 · 가로 스크롤 · 복사", """\[ \int_{-\infty}^{\infty} e^{-x^2} \, dx = \sqrt{\pi} \]""")

        question("행렬식은?")
        answer("블록 수식 · 큰 구조", """\[ \det \begin{pmatrix} a & b \\ c & d \end{pmatrix} = ad - bc \]""")

        question("피보나치 수열을 Kotlin으로 구현해줘")
        // Android 추가: iOS 원본은 Swift다. Kotlin·Swift 두 언어를 나란히 둔다.
        answer(
            "코드 블록 · 언어 라벨 · 가로 스크롤",
            """
            점화식은 \( F_n = F_{n-1} + F_{n-2} \)입니다.

            ```kotlin
            fun fibonacci(n: Int): Long {
                if (n < 2) return n.toLong()
                var (previous, current) = 0L to 1L
                repeat(n - 1) { val next = previous + current; previous = current; current = next }
                return current
            }
            // 아주 긴 줄: 가로 스크롤이 동작하는지 확인한다. abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz
            ```

            ```swift
            func fibonacci(_ n: Int) -> Int {
                guard n > 1 else { return n }
                var (previous, current) = (0, 1)
                for _ in 2...n { (previous, current) = (current, previous + current) }
                return current
            }
            ```

            언어 라벨이 없는 블록도 확인합니다.

            ```
            plain fence, no language
            ```
            """,
        )

        question("마크다운 블록 요소를 전부 보여줘")
        answer(
            "헤딩 · 리스트 · 인용 · 구분선 · 링크",
            """
            # 헤딩 1
            ## 헤딩 2
            ### 헤딩 3

            **굵게**, *기울임*, ~~취소선~~, `인라인 코드`, [절대 URL 링크](https://example.com).

            영문으로도 확인: **bold**, *italic*, ~~strikethrough~~,
            ***bold italic***, **굵게 안의 *기울임***.

            이스케이프: \*별표\*, \_밑줄\_, \$100, 경로 C:\\temp

            - 순서 없는 항목 \(x_1\)
            - 중첩 없는 두 번째 항목
            - 세 번째 항목

            1. 순서 있는 항목
            2. 두 번째
            3. 세 번째

            > 인용문 안의 수식 \( e^{i\pi} + 1 = 0 \)
            > 두 줄짜리 인용문입니다.

            ---

            구분선 아래 문단입니다.
            """,
        )

        question("링크는 어떤 것만 열려?")
        answer(
            "링크 allowlist · plain text 강등",
            """
            허용: [https](https://example.com), [http](http://example.com),
            [mailto](mailto:someone@example.com).

            비허용(plain text로 표시): [ftp](ftp://example.com),
            [javascript](javascript:alert(1)), [상대 경로](/relative/path),
            [tel](tel:01012345678).

            이미지 문법은 alt text만 표시합니다: ![대체 텍스트만 보입니다](https://example.com/i.png)
            """,
        )

        question("수식이 코드나 링크 안에 있으면?")
        answer(
            "금지 문맥 보호",
            """
            인라인 코드 안: `\(x\)` 는 수식이 아니라 코드입니다.

            코드 블록 안:

            ```text
            \(x + y\)
            \[ z \]
            ```

            링크 라벨 안: [\(x\)](https://example.com) 도 수식이 아닙니다.

            반대로 수식이 link-like source를 감싸면 수식입니다: \([a](b)\)

            HTML은 실행하지 않고 문자 그대로 표시합니다.

            <div onclick="alert(1)">raw html</div>
            """,
        )

        question("Markdown 기호가 수식 안에 있으면 깨져?")
        answer(
            "Markdown 기호 포함 수식",
            """
            곱셈 별표: \(a * b\) 와 \(c * d\) — 기울임으로 깨지지 않습니다.

            밑줄과 대괄호: \(x_[i]\), \(y_{j}\)

            백틱 포함: \(f(x) = x^2\) 옆의 `code`
            """,
        )

        question("$ 기호로 쓴 수식도 되나?")
        answer("Dollar math (opt-in) · 통화 표기", dollarMath)

        question("이상한 수식을 넣으면 어떻게 돼?")
        answer(
            "실패 시 원문 표시 (fail-open)",
            """
            미완성: \(x + y 는 닫히지 않았습니다.

            빈 수식: \(\) 도 원문 그대로입니다.

            중첩: \(a \(b\) c\)

            렌더 불가한 LaTeX: \(\frac{\) 와 \(\unknowncommand{x}\)

            문단 전체가 아닌 위치의 display 구분자: 이건 \[x+y\] 인라인 위치입니다.
            """,
        )

        question("한국어·영어·이모지·RTL이 섞이면?")
        answer(
            "다국어 · 결합 문자 · RTL",
            """
            한글과 English와 이모지 🙂🇰🇷 그리고 RTL עברית 사이에
            \(\sum_{k=0}^{n} \binom{n}{k} = 2^n \) 을 넣습니다.

            결합 문자: é (e + U+0301) 와 café 그리고 \(\hat{x}\)

            긴 한글 문단 안에서도 인라인 수식 \(\lim_{n \to \infty} \frac{1}{n} = 0\) 의
            baseline과 줄바꿈이 자연스러워야 합니다.
            """,
        )

        question("표는 지원해?")
        answer(
            "GFM 표 · 정렬 · 인라인 콘텐츠",
            """
            GFM 표를 지원합니다. 셀 안의 강조, 코드, 인라인 수식도 함께 렌더링합니다.

            | 항목 | 수식 | 상태 |
            | :--- | :---: | ---: |
            | **하나** | \(x+1\) | 완료 |
            | 둘 | `code` | 2 |
            """,
        )

        question("긴 답변도 잘 나와?")
        answer(
            "긴 답변 · 다수 수식 · 스크롤",
            """
            ## 요약

            긴 답변에서 수식이 많아도 렌더가 유지되는지 확인합니다.

            1. 미분: \(\frac{d}{dx} x^n = n x^{n-1}\)
            2. 적분: \(\int x^n dx = \frac{x^{n+1}}{n+1} + C\)
            3. 극한: \(\lim_{x \to 0} \frac{\sin x}{x} = 1\)
            4. 급수: \(\sum_{n=1}^{\infty} \frac{1}{n^2} = \frac{\pi^2}{6}\)

            \[ e^{x} = \sum_{n=0}^{\infty} \frac{x^n}{n!} \]

            위 항등식은 모든 실수 \(x\)에 대해 성립하며, 복소수 \(z\)로 확장하면
            \( e^{iz} = \cos z + i \sin z \) 를 얻습니다.

            ```python
            import math
            print(sum(1 / n ** 2 for n in range(1, 100000)))  # ~ pi^2 / 6
            ```

            > 마지막 인용: 수식 \(\pi^2/6 \approx 1.6449\)
            """,
        )
    }

    /** iOS `CodeBlockExtensionDemo.swift` `CodeBlockExtensionDemoView.sample` — 수식·표·코드·Mermaid 혼합. */
    val codeBlockExtensions: String = """
        # 검색 파이프라인

        질의 하나가 응답까지 가는 경로입니다. 색인 크기가 \(n\)일 때 이진 탐색 비용은
        \(O(\log n)\)입니다.

        ```mermaid
        flowchart TD
            Q[사용자 질의] --> N[정규화]
            N --> R{캐시 적중?}
            R -->|예| C[캐시 응답]
            R -->|아니오| S[색인 검색]
            S --> K[상위 k개 재순위]
            K --> A[응답 생성]
            C --> A
        ```

        재순위 단계는 아래처럼 구현합니다.

        ```kotlin
        // 상위 k개만 남기고 점수 순으로 정렬한다.
        fun rerank(hits: List<Hit>, limit: Int = 20): List<Hit> =
            hits.sortedByDescending { it.score }
                .take(limit)
                .map { Hit(id = it.id, score = it.score) }
        ```

        ```swift
        // 상위 k개만 남기고 점수 순으로 정렬한다.
        func rerank(_ hits: [Hit], limit: Int = 20) -> [Hit] {
            hits.sorted { $0.score > $1.score }
                .prefix(limit)
                .map { Hit(id: $0.id, score: $0.score) }
        }
        ```

        호출 예시와 응답 형식은 다음과 같습니다.

        ```bash
        curl -sS "https://example.com/search?q=검색&k=20" | jq '.hits[0]'
        ```

        ```json
        {
          "query": "검색",
          "took_ms": 12,
          "hits": [{ "id": "doc-1", "score": 0.94, "title": "색인 설계" }]
        }
        ```

        ```python
        def normalize(query: str) -> str:
            # 공백과 대소문자를 정리한다.
            return " ".join(query.strip().lower().split())
        ```

        ## 응답 시간 예산

        | 단계 | 예산(ms) | 비고 |
        | --- | ---: | --- |
        | 정규화 | 1 | 문자열만 |
        | 색인 검색 | 8 | \(O(\log n)\) |
        | 재순위 | 3 | 상위 k개 |

        다음은 시퀀스로 본 같은 흐름입니다.

        ```mermaid
        sequenceDiagram
            participant 사용자
            participant 앱
            participant 색인
            사용자->>앱: 질의 전송
            앱->>색인: 검색 요청
            색인-->>앱: 상위 k개
            앱-->>사용자: 응답 생성
        ```

        지원하지 않는 언어는 원문 그대로 표시합니다.

        ```brainfuck
        ++++++++[>++++[>++>+++>+++>+<<<<-]>+>+>->>+[<]<-]
        ```
    """.trimIndent()

    /** iOS `SSEDemo.swift` `SSEDemoFixtures.answer` — 스트리밍 재생·SSE 로컬 시뮬레이션 본문. */
    val streamingAnswer: String = """
        ## 정규분포의 넓이

        확률밀도함수는 \( f(x) = \frac{1}{\sigma\sqrt{2\pi}} e^{-\frac{(x-\mu)^2}{2\sigma^2}} \)
        입니다. 전체 넓이가 1이 되는 이유는 가우스 적분에서 옵니다.

        \[ \int_{-\infty}^{\infty} e^{-x^2} \, dx = \sqrt{\pi} \]

        표준화하면 \(z = \frac{x - \mu}{\sigma}\) 이고, 구간별 확률은 다음과 같습니다.

        | 구간 | 확률 | 비고 |
        | :--- | :---: | ---: |
        | \(\mu \pm \sigma\) | 68.27% | 1 시그마 |
        | \(\mu \pm 2\sigma\) | 95.45% | 2 시그마 |
        | \(\mu \pm 3\sigma\) | 99.73% | 3 시그마 |

        Kotlin으로는 이렇게 계산합니다.

        ```kotlin
        fun normalPdf(x: Double, mean: Double = 0.0, sd: Double = 1.0): Double {
            val z = (x - mean) / sd
            return exp(-0.5 * z * z) / (sd * sqrt(2 * PI))
        }
        ```

        정리하면

        1. 밀도는 \(e^{-z^2/2}\) 에 비례합니다.
        2. 정규화 상수는 \(\frac{1}{\sigma\sqrt{2\pi}}\) 입니다.
        3. 누적분포 \(\Phi(z)\) 는 닫힌 형태가 없어 수치적으로 구합니다.

        > 오차함수로 쓰면 \( \Phi(z) = \frac{1}{2}\left[1 + \mathrm{erf}\left(\frac{z}{\sqrt{2}}\right)\right] \) 입니다.
    """.trimIndent()

    /** Android 추가: 수식 안 `\text{한글}` — KaTeX 폰트에 없는 글리프가 시스템 폰트로 fallback되는지 확인. */
    val koreanInMath: String = """
        수식 안의 한글은 `\text{}`로 넣습니다: \( \text{넓이} = \pi r^2 \), \( \text{속도} = \frac{\text{거리}}{\text{시간}} \).

        \[ \text{평균} = \frac{1}{n} \sum_{i=1}^{n} x_i \]

        본문 한글과 수식 baseline이 한 줄에서 맞아야 합니다: 값은 \(x = 3\)이고 단위는 \(\text{cm}\)입니다.
    """.trimIndent()

    /** 쇼케이스 화면 구획. 답변 fixture 전부 + 코드 블록 확장 + 한글 수식. */
    val showcaseSections: List<SampleSection> =
        conversation.filter { it.role == ChatMessage.Role.Assistant }.map { SampleSection(it.caseName, it.text) } +
            SampleSection("코드 블록 확장 · Prism · Mermaid", codeBlockExtensions) +
            SampleSection("수식 안 한글 · \\text{}", koreanInMath)

    /**
     * grapheme 경계로 자른다 (iOS `SSEDemoFixtures.chunks` = `Character` 경계).
     * UTF-16 단위로 자르면 이모지·결합 문자가 깨진다.
     */
    fun graphemeChunks(text: String, size: Int = 6): List<String> {
        require(size > 0)
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var count = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            current.append(text, start, end)
            if (++count == size) {
                chunks += current.toString()
                current.setLength(0)
                count = 0
            }
            start = end
            end = iterator.next()
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}

/** 데모 전체가 같은 인스턴스를 쓴다. `RichMarkdownCodeBlockOptions`는 참조 동일성으로 비교한다. */
fun demoCodeBlocks(context: Context): RichMarkdownCodeBlockOptions = RichMarkdownCodeBlockOptions(
    highlighter = PrismHighlighter.shared(context),
    diagram = MermaidDiagramRenderer.shared,
)
