// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.core.fixtures

import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 공용 fixture 코퍼스 접근 (iOS `Tests/Fixtures/README.md` §1·§2·§4·§5).
 * 코퍼스는 `scripts/sync-ios-fixtures.sh`가 `src/test/resources/fixtures/`에 복사하며, 파일 목록은
 * classpath에서 열거할 수 없으므로 `manifest.json`으로 읽는다. 파일 id는 확장자를 뺀 상대 경로다.
 */
internal object FixtureSupport {
    private const val ROOT = "fixtures"
    const val OUT_ENV: String = "RICHMARKDOWN_FIXTURE_OUT"
    const val PLATFORM: String = "android"

    /** manifest.json의 전체 상대 경로. */
    val manifestPaths: List<String> by lazy {
        val manifest = MiniJson.parse(readText("manifest.json") ?: error("fixtures/manifest.json 없음 — scripts/sync-ios-fixtures.sh 실행"))
        (manifest.asMap()["files"] as List<*>).map { (it.asMap()["path"] as String) }
    }

    /** `<category>/` 아래 `.md`의 id(사전순). `security/mermaid/`는 Mermaid 모듈 몫이라 제외한다. */
    fun markdownIds(category: String): List<String> = manifestPaths
        .filter { it.startsWith("$category/") && it.endsWith(".md") && !it.startsWith("security/mermaid/") }
        .map { it.removeSuffix(".md") }
        .sorted()

    /** `pathological/` 아래 `.gen.json`의 id(사전순). */
    fun generatorIds(): List<String> = manifestPaths
        .filter { it.startsWith("pathological/") && it.endsWith(".gen.json") }
        .map { it.removeSuffix(".gen.json") }
        .sorted()

    /** 리소스를 바이트로 읽어 UTF-8로 복원한다. BOM·CR·NUL을 그대로 보존한다. 없으면 null. */
    fun readText(path: String): String? =
        FixtureSupport::class.java.classLoader.getResourceAsStream("$ROOT/$path")
            ?.use { String(it.readBytes(), StandardCharsets.UTF_8) }

    fun readMarkdown(id: String): String = readText("$id.md") ?: error("fixture 없음: $id.md")

    fun meta(id: String): FixtureMeta =
        readText("$id.meta.json")?.let { FixtureMeta.from(MiniJson.parse(it).asMap()) } ?: FixtureMeta.DEFAULT

    /** `parity-exceptions.json`이 id별로 승인한 필드 이름(`href` 등). URL 구현 차이처럼 플랫폼마다 값이 다른 필드다. */
    fun ignoredFields(id: String): Set<String> {
        val root = MiniJson.parse(readText("parity-exceptions.json") ?: return emptySet()).asMap()
        val entry = (root["exceptions"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull { it["id"] == id }
        return (entry?.get("ignore") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
    }

    /** 승인된 필드의 값을 같은 표식으로 바꿔 비교에서 뺀다 (iOS FixtureSupport.masking과 같은 규칙). */
    fun masking(value: Any?, fields: Set<String>): Any? = when {
        fields.isEmpty() -> value
        value is Map<*, *> -> value.entries.associate { (k, v) -> k to if (k in fields) "<ignored>" else masking(v, fields) }
        value is List<*> -> value.map { masking(it, fields) }
        else -> value
    }

    /** `<id>.expected.json`이 있으면 파싱한 트리, 없으면 null. */
    fun expected(id: String): Any? = readText("$id.expected.json")?.let(MiniJson::parse)

    fun generatorRule(id: String): Map<String, Any?> =
        MiniJson.parse(readText("$id.gen.json") ?: error("규칙 없음: $id.gen.json")).asMap()

    /** 병리 입력 생성 (README §5). */
    fun generate(rule: Map<String, Any?>): String {
        fun str(key: String, default: String = ""): String = rule[key] as String? ?: default
        fun int(key: String): Int = (rule[key] as Number).toInt()
        return when (val kind = str("kind")) {
            "repeat" -> str("unit").repeat(int("count")) + str("suffix")
            "repeat-lines" -> str("prefix") + List(int("count")) { str("line") }.joinToString("\n")
            "nested-list" -> List(int("depth")) { i -> str("indent").repeat(i) + str("item") }.joinToString("\n")
            "math-oversize" -> str("open") + str("fill").repeat(int("count")) + str("close")
            "table" -> table(columns = int("columns"), rows = int("rows"), cell = str("cell"))
            else -> error("알 수 없는 생성 규칙 kind: $kind")
        }
    }

    private fun table(columns: Int, rows: Int, cell: String): String {
        val line = List(columns) { cell }.joinToString(" | ", prefix = "| ", postfix = " |")
        val separator = List(columns) { "---" }.joinToString("|", prefix = "|", postfix = "|")
        return (listOf(line, separator) + List(rows) { line }).joinToString("\n")
    }

    /** `RICHMARKDOWN_FIXTURE_OUT/android`. 환경 변수가 없으면 null(출력 생략). */
    val outputDir: File? by lazy {
        System.getenv(OUT_ENV)?.takeIf { it.isNotBlank() }?.let { File(it, PLATFORM) }
    }

    /** 정규화 JSON을 `<out>/android/<id>.json`에 쓴다 (README §3·§4). */
    fun writeOutput(id: String, normalized: Any?) {
        val dir = outputDir ?: return
        val file = File(dir, "$id.json")
        file.parentFile.mkdirs()
        file.writeText(MiniJson.write(normalized), StandardCharsets.UTF_8)
    }

    fun utf8ByteCount(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    @Suppress("UNCHECKED_CAST")
    fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>
}

/** 사이드카 `.meta.json`과 `.gen.json`이 공유하는 옵션·예산·기대값 (README §2·§5). */
internal data class FixtureMeta(
    val dollarMath: DollarMathOptions,
    val budgetMs: Long,
    val expect: Map<String, Any?>,
) {
    companion object {
        const val DEFAULT_BUDGET_MS: Long = 1000
        val DEFAULT: FixtureMeta = FixtureMeta(DollarMathOptions.None, DEFAULT_BUDGET_MS, emptyMap())

        fun from(map: Map<String, Any?>): FixtureMeta = FixtureMeta(
            dollarMath = dollarMath(map["dollarMath"] as List<*>?),
            budgetMs = (map["budgetMs"] as Number?)?.toLong() ?: DEFAULT_BUDGET_MS,
            expect = (map["expect"] as Map<*, *>?)?.let { expect -> expect.entries.associate { it.key as String to it.value } }
                ?: emptyMap(),
        )

        private fun dollarMath(names: List<*>?): DollarMathOptions =
            names.orEmpty().fold(DollarMathOptions.None) { acc, name ->
                acc + when (name) {
                    "single" -> DollarMathOptions.Single
                    "inlineDouble" -> DollarMathOptions.InlineDouble
                    else -> error("알 수 없는 dollarMath 값: $name")
                }
            }
    }
}
