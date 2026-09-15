// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import io.github.jimmyjung.richmarkdown.core.ParsedDocument

/**
 * parse 결과 캐시 (iOS `ParseCache.swift`). 파서는 순수 함수라 (markdown, dollarMath, wasTruncated)가
 * 결과를 결정한다.
 *
 * 셀 재사용과 Compose 재구성은 같은 원문을 반복 제출한다. raster 캐시([MathRenderService])만으로는
 * 매번 재파싱 + 2단계 게시(원문 fallback → hydration)가 남아 스크롤 중 뷰 재구성이 2회씩 일어난다.
 * parse까지 캐시하면 [RichMarkdownRenderModel]이 완성 상태를 1회에 게시할 수 있다.
 *
 * 모든 메서드는 스레드 안전하다(main·worker 양쪽에서 접근).
 */
internal object ParseCache {
    /** worker에서 만들어 main의 generation-guarded store에 전달하는 불변 key. */
    class Key internal constructor(val value: String, val sourceByteCount: Int)

    private class Entry(val document: ParsedDocument, val cost: Int)

    // ponytail: 상한은 잠정값(iOS 동일 16 MiB). cost는 원문 3배 + 문서 node 추정치다.
    private const val MAX_TOTAL_COST = 16 * 1024 * 1024

    // ponytail: android.util.LruCache는 JVM 단위 테스트에서 stub이라 같은 구조(access-order
    // LinkedHashMap + cost)를 직접 둔다. 교체하려면 이 두 필드와 store/document/removeAll만 바꾼다.
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalCost = 0

    /** dollar 옵션 조합마다 결과가 다르므로 rawValue를 key 접두에 넣는다: `"${rawValue}${T|-}$markdown"`. */
    fun key(markdown: String, dollarMath: LatexDollarMathOptions, wasTruncated: Boolean): Key {
        val prefix = "${dollarMath.rawValue}${if (wasTruncated) "T" else "-"}"
        return Key(value = prefix + markdown, sourceByteCount = markdown.toByteArray(Charsets.UTF_8).size)
    }

    fun document(key: Key): ParsedDocument? = synchronized(lock) { entries[key.value]?.document }

    fun store(key: Key, document: ParsedDocument) {
        val cost = estimatedCost(document, key.sourceByteCount)
        synchronized(lock) {
            entries.remove(key.value)?.let { totalCost -= it.cost }
            entries[key.value] = Entry(document, cost)
            totalCost += cost
            val iterator = entries.entries.iterator()
            while (totalCost > MAX_TOTAL_COST && iterator.hasNext()) {
                totalCost -= iterator.next().value.cost
                iterator.remove()
            }
        }
    }

    fun removeAll() = synchronized(lock) {
        entries.clear()
        totalCost = 0
    }

    /**
     * 원문 복사·AST·배열/수식 node가 함께 cache에 남는 점을 반영한 보수적 비용이다.
     * 엄격한 메모리 측정값이 아니므로 작은 cache를 일찍 축출하는 쪽을 택한다. 포화 산술.
     */
    internal fun estimatedCost(document: ParsedDocument, sourceByteCount: Int): Int {
        val total = maxOf(sourceByteCount, 1).toLong() * 3 +
            document.blocks.size.toLong() * 512 +
            document.allMathSegments.size.toLong() * 1_024
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
