// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

/**
 * 수식 서체.
 *
 * **플랫폼 차이(문서화된 격차)**: iOS는 SwiftMath가 번들한 12종(latinModern·xits·fira …)을 고를 수
 * 있지만, Android 수식 엔진(D4, RaTeX)은 KaTeX 폰트 세트만 싣고 외부 서체 주입을 지원하지 않는다.
 * 따라서 값은 하나다. enum으로 두는 이유는 iOS `RichMarkdownTheme.mathFont`와 API 형태를 맞추고,
 * 값이 raster cache key에 들어가는 계약을 유지하기 위함이다.
 */
enum class LatexMathFont {
    KaTeX,
}
