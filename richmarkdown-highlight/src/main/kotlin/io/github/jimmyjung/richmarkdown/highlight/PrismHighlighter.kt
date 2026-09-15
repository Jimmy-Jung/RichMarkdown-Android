// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.highlight

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightKind
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightSpan
import io.github.jimmyjung.richmarkdown.RichMarkdownSyntaxHighlighting
import io.github.jimmyjung.richmarkdown.core.Utf16Range
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private typealias Kind = RichMarkdownHighlightKind

/**
 * 번들에 고정한 Prism 1.30.0을 QuickJS에서 실행해 코드 블록의 색 범위를 만든다
 * (iOS `Sources/RichMarkdownHighlight/PrismHighlighter.swift` 포팅, Docs/CODE_BLOCK_EXTENSIONS.md §3.2–3.4·§4).
 *
 * 사용자 코드는 실행할 스크립트에 이어 붙이지 않는다. `__richmarkdown.source()` / `language()` 바인딩이
 * Kotlin 필드를 돌려주고, JS 쪽은 `nativeTokenize(...)`의 **인자**로만 받는다. `evaluate`에 사용자 입력이
 * 문자열로 섞이는 일이 없다.
 *
 * ```kotlin
 * RichMarkdownCodeBlockOptions(highlighter = PrismHighlighter.shared(context))
 * ```
 *
 * 실패와 미지원 언어는 던지지 않고 **빈 리스트**다 — 호출한 렌더러가 원문을 그대로 둔다.
 * 앱 하나에 JS 컨텍스트 하나이며 모든 호출은 [mutex]로 직렬화된다 (iOS actor와 동일).
 */
class PrismHighlighter private constructor(
    private val appContext: Context,
) : RichMarkdownSyntaxHighlighting {

    private val mutex = Mutex()
    private var quickJs: QuickJs? = null

    // 바인딩이 읽는 현재 요청. mutex 안에서만 쓰고 읽는다.
    @Volatile private var pendingSource = ""
    @Volatile private var pendingLanguage = ""

    // ponytail: release()가 토큰화 중에 불리면 그 호출이 끝난 뒤 닫는다. 닫는 시점의 정확도는 필요 없다.
    @Volatile private var releaseRequested = false

    override suspend fun spans(code: String, language: String): List<RichMarkdownHighlightSpan> {
        val normalized = language.lowercase()
        val grammar = aliases[normalized] ?: normalized
        if (code.isEmpty() || code.length > maxCodeUtf16Units) return emptyList()

        return withContext(Dispatchers.Default) {
            mutex.withLock {
                try {
                    tokenize(code, grammar)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 번들 손상·QuickJS 예외·토큰 불일치 — 어느 쪽이든 원문을 그대로 표시한다.
                    Log.w(TAG, "하이라이팅 실패($grammar): ${e.message}")
                    emptyList()
                } finally {
                    if (releaseRequested) {
                        releaseRequested = false
                        closeContext()
                    }
                }
            }
        }
    }

    /**
     * JS 컨텍스트를 닫는다. 다음 호출에서 다시 초기화된다 (iOS `reset()`).
     * 토큰화가 진행 중이면 그 호출이 끝난 직후 닫는다.
     */
    fun release() {
        if (!mutex.tryLock()) {
            releaseRequested = true
            return
        }
        try {
            closeContext()
        } finally {
            mutex.unlock()
        }
    }

    // MARK: - QuickJS

    /** 호출자는 [mutex]를 잡고 있어야 한다. */
    private suspend fun tokenize(code: String, grammar: String): List<RichMarkdownHighlightSpan> {
        val js = loadedContext()
        pendingSource = code
        pendingLanguage = grammar
        // 미지원 언어는 실패가 아니라 정상 경로다. 로그 없이 원문을 둔다.
        if (!js.evaluate<Boolean>("nativeHasGrammar(__richmarkdown.language())")) return emptyList()
        val chunks = js.evaluate<List<Any?>>("nativeTokenize(__richmarkdown.source(), __richmarkdown.language())")
        return validatedSpans(chunks, code)
    }

    private suspend fun loadedContext(): QuickJs {
        quickJs?.let { return it }
        val created = QuickJs.create(Dispatchers.Default)
        try {
            for (name in scripts) {
                val script = appContext.assets.open("prism/$name.js").bufferedReader().use { it.readText() }
                created.evaluate<Any?>(script + SCRIPT_TERMINATOR, filename = "$name.js")
            }
            created.define("__richmarkdown") {
                function("source") { pendingSource }
                function("language") { pendingLanguage }
            }
        } catch (e: Throwable) {
            created.close()
            throw e
        }
        quickJs = created
        return created
    }

    private fun closeContext() {
        quickJs?.close()
        quickJs = null
    }

    // MARK: - 검증

    /**
     * 조각을 UTF-16 범위로 바꾸고, 이어 붙인 결과가 원문과 **정확히** 같은지 확인한다.
     * 한 글자라도 다르면 범위 전체를 버린다 — 잘못 밀린 색을 표시하지 않는다.
     */
    private fun validatedSpans(chunks: List<Any?>, source: String): List<RichMarkdownHighlightSpan> {
        val rebuilt = StringBuilder(source.length)
        val spans = ArrayList<RichMarkdownHighlightSpan>()
        for (chunk in chunks) {
            val fields = chunk as? Map<*, *>
            val content = fields?.get("content") as? String
            val type = fields?.get("kind") as? String
            if (content == null || type == null) error("토큰 필드 누락")
            val kind = kindFor(type)
            if (content.isNotEmpty() && kind != null) {
                spans += RichMarkdownHighlightSpan(Utf16Range(rebuilt.length, rebuilt.length + content.length), kind)
            }
            rebuilt.append(content)
        }
        if (!rebuilt.contentEquals(source)) error("토큰 원문 불일치")
        return spans
    }

    companion object {
        private const val TAG = "RichMarkdown"

        /**
         * Prism 파일은 IIFE 결과(순환 참조 Prism 객체)를 완료값으로 남기고 quickjs-kt는 완료값을 Kotlin으로
         * 변환하다 `TypeError: circular reference`를 낸다 (P0 spike 실측). 완료값을 undefined로 덮는다.
         */
        private const val SCRIPT_TERMINATOR = "\n;undefined;\n"

        /** 정규식 기반 토큰화의 상한. 코드 블록 하나가 이보다 길면 색을 입히지 않고 원문을 둔다. */
        const val maxCodeUtf16Units: Int = 100_000

        @Volatile private var instance: PrismHighlighter? = null

        /** 앱 하나에 컨텍스트 하나. `applicationContext`에 묶인 싱글턴이다. */
        fun shared(context: Context): PrismHighlighter =
            instance ?: synchronized(this) {
                instance ?: PrismHighlighter(context.applicationContext).also { instance = it }
            }

        /** 로드 순서가 곧 의존 관계다. `clike`·`markup`·`javascript`·`c`는 자신을 확장하는 문법보다 먼저 온다. */
        private val scripts = listOf(
            "prism-core", "prism-clike", "prism-markup", "prism-css", "prism-javascript", "prism-jsx",
            "prism-typescript", "prism-tsx", "prism-swift", "prism-python", "prism-json", "prism-bash",
            "prism-kotlin", "prism-java", "prism-c", "prism-cpp", "prism-go", "prism-rust", "prism-sql",
            "prism-yaml", "native-tokenize",
        )

        /**
         * Prism이 스스로 등록하지 않는 별칭만 담는다 (Docs §4.2).
         * `js`·`ts`·`py`·`sh`·`shell`·`kt`·`kts`·`yml`·`html`·`xml`·`svg`는 문법 파일이 직접 등록한다.
         */
        private val aliases = mapOf(
            "c++" to "cpp", "cxx" to "cpp", "cc" to "cpp", "objective-c++" to "cpp",
            "golang" to "go",
            "rs" to "rust",
            "zsh" to "bash", "console" to "bash", "shell-session" to "bash",
            "json5" to "json", "jsonc" to "json",
            "mysql" to "sql", "postgres" to "sql", "postgresql" to "sql", "sqlite" to "sql",
            "htm" to "markup",
            "node" to "javascript",
        )

        /**
         * Prism 토큰 이름 → 테마 역할 7종 (Swift `kind(for:)`, Docs §4.3). 매핑이 없으면 null이고 본문 색을 유지한다.
         * `operator`·`punctuation`은 일부러 제외한다 — 색을 주면 코드 대부분이 물들어 강조 대비가 사라진다.
         * Prism은 `class-name` 같은 하이픈 이름과 `string.special` 같은 점 표기를 함께 쓰므로 점 앞 카테고리로 본다.
         */
        internal fun kindFor(prismType: String): Kind? = when (prismType.substringBefore('.')) {
            "keyword", "boolean", "constant", "atrule", "important", "null", "nil",
            "literal", "import", "module-declaration", "static", "directive",
            "directive-hash", "directive-name", "other-directive", "rule", "at",
            -> Kind.Keyword

            "string", "char", "character", "regex", "regex-source", "regex-delimiter",
            "regex-flags", "string-literal", "template-string", "triple-quoted-string",
            "raw-string", "attr-value", "url", "scalar",
            -> Kind.String

            "comment", "doc-comment", "prolog", "doctype", "cdata", "shebang", "hashbang",
            -> Kind.Comment

            "number", "float", "color", "datetime",
            -> Kind.Number

            "class-name", "known-class-name", "builtin", "namespace", "constructor",
            "type-definition", "tag", "doctype-tag", "generic", "generics",
            "generic-function", "lifetime-annotation", "base-clause", "module",
            "entity", "named-entity", "package",
            -> Kind.Type

            "function", "function-name", "function-variable", "function-definition",
            "method", "macro", "macro-name",
            -> Kind.Function

            "property", "literal-property", "string-property", "attr-name", "attribute",
            "annotation", "decorator", "symbol", "label", "variable", "parameter",
            "property-access", "key", "selector", "environment",
            -> Kind.Property

            else -> null
        }
    }
}
