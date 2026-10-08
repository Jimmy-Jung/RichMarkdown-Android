// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

/** iOS `NSRange(location:length:)` 축약. */
internal fun r(location: Int, length: Int) = EditorRange(location, length)

internal val BlockEditorModel.texts: List<String> get() = blocks.map { it.text }

internal val BlockEditorModel.kinds: List<EditorBlockKind> get() = blocks.map { it.kind }

/** iOS 매개변수화 테스트의 overflow·음수 범위 4종. */
internal val OVERFLOW_RANGES = listOf(r(Int.MAX_VALUE, 1), r(1, Int.MAX_VALUE), r(-1, 1), r(0, -1))
