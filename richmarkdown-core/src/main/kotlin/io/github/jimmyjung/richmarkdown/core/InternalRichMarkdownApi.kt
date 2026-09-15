// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

/**
 * `richmarkdown-core`는 Maven 아티팩트로 공개되지만 공개 parser/AST product는 아니다
 * (iOS DEVELOPMENT.md §1 비목표, D2). iOS의 `package` 접근 수준을 대신해 이 opt-in으로 표시한다.
 * 렌더 모듈은 build.gradle.kts에서 일괄 opt-in하고, 외부 소비자는 명시적으로 opt-in해야 한다.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "richmarkdown-core 내부 API다. minor 버전에서도 바뀔 수 있다.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalRichMarkdownApi
