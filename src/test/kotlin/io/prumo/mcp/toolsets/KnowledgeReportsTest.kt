package io.prumo.mcp.toolsets

import io.prumo.mcp.knowledge.Freshness
import io.prumo.mcp.knowledge.KnowledgeRecord
import io.prumo.mcp.knowledge.Provenance
import io.prumo.mcp.knowledge.SourceKind
import io.prumo.mcp.knowledge.SourceStamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A resposta da busca na memória.
 *
 * O score existe para o cliente calibrar confiança: sem ele, um resultado fraco e um forte chegam
 * com a mesma aparência, e a ordenação por relevância vira uma ordem que ninguém consegue explicar.
 */
class KnowledgeReportsTest {

    @Test
    fun `o score de cada resultado chega ao cliente`() {
        val matches = listOf(
            match("forte", Freshness.FRESH, 2.5f),
            match("fraco", Freshness.FRESH, 0.4f),
        )

        val response = KnowledgeReports.search("w", stored = 2, matches = matches, maxResults = 10)

        assertEquals(listOf(2.5f, 0.4f), response.results.map { it.score })
        assertEquals(listOf("forte", "fraco"), response.results.map { it.knowledgeId })
    }

    @Test
    fun `busca sem texto nao inventa score`() {
        val response = KnowledgeReports.search(
            "w",
            stored = 1,
            matches = listOf(match("unico", Freshness.FRESH, score = null)),
            maxResults = 10,
        )

        assertNull(response.results.single().score)
    }

    @Test
    fun `as contagens cobrem o recorte inteiro, e nao a janela`() {
        val matches = (1..5).map { match("r$it", if (it % 2 == 0) Freshness.STALE else Freshness.FRESH, 1f) }

        val response = KnowledgeReports.search("w", stored = 9, matches = matches, maxResults = 2)

        assertEquals(2, response.results.size)
        assertEquals(5, response.matchCount)
        assertEquals(9, response.storedCount)
        assertEquals(mapOf("FRESH" to 3, "STALE" to 2), response.byFreshness)
        assertTrue(response.truncated)
    }

    @Test
    fun `janela maior que o resultado nao se diz truncada`() {
        val response = KnowledgeReports.search(
            "w",
            stored = 1,
            matches = listOf(match("unico", Freshness.ORPHAN, 1f)),
            maxResults = 50,
        )

        assertFalse(response.truncated)
        assertEquals(mapOf("ORPHAN" to 1), response.byFreshness)
    }

    @Test
    fun `a busca nao devolve o corpo do registro`() {
        val response = KnowledgeReports.search(
            "w",
            stored = 1,
            matches = listOf(match("unico", Freshness.FRESH, 1f)),
            maxResults = 10,
        )

        assertNull(response.results.single().body, "o corpo se lê por identificador, não na busca")
    }

    private fun match(id: String, freshness: Freshness, score: Float?) = KnowledgeMatch(
        record = KnowledgeRecord(
            schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION,
            id = id,
            title = "Título de $id",
            body = "Corpo de $id",
            tags = listOf("etiqueta"),
            provenance = Provenance(
                sourceKind = SourceKind.REPOSITORY,
                sourceId = "repositorio",
                stamp = SourceStamp(sizeBytes = 1, modifiedAtEpochMillis = 0, sha256 = "abc"),
            ),
            author = "teste",
            createdAt = "2026-09-07T00:00:00Z",
            updatedAt = "2026-09-07T00:00:00Z",
        ),
        freshness = freshness,
        score = score,
    )
}
