package io.prumo.mcp.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A composição entre os recortes exatos e a busca por relevância.
 *
 * O que estes testes protegem é a ordem: etiqueta e origem recortam primeiro, e o motor pontua só o
 * que sobrou. Pontuar tudo e recortar depois devolveria resultado de aparência normal, com o score
 * e as contagens medidos sobre registros que o cliente não pediu.
 */
class KnowledgeRecallTest {

    @Test
    fun `o motor so enxerga o que a etiqueta deixou passar`() {
        val search = RecordingSearch()
        val records = listOf(
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Folha de pagamento", tags = listOf("cadastro")),
        )

        recallRecords(records, tag = "folha", sourceId = null, query = "folha", search = search)

        assertEquals(listOf("alfa"), search.seen.map { it.id })
    }

    @Test
    fun `o motor so enxerga o que a origem deixou passar`() {
        val search = RecordingSearch()
        val records = listOf(
            record("alfa", "Folha de pagamento", sourceId = "repositorio:um"),
            record("beta", "Folha de pagamento", sourceId = "repositorio:dois"),
        )

        recallRecords(records, tag = null, sourceId = "repositorio:dois", query = "folha", search = search)

        assertEquals(listOf("beta"), search.seen.map { it.id })
    }

    @Test
    fun `texto com etiqueta devolve so o registro da etiqueta`() {
        val records = listOf(
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Folha de pagamento", tags = listOf("cadastro")),
        )

        val found = recallRecords(records, tag = "folha", sourceId = null, query = "folha", search = RecordingSearch())

        assertEquals(listOf("alfa"), found.records.map { it.first.id })
    }

    @Test
    fun `texto com origem devolve so o registro daquela origem`() {
        val records = listOf(
            record("alfa", "Folha de pagamento", sourceId = "repositorio:um"),
            record("beta", "Folha de pagamento", sourceId = "repositorio:dois"),
        )

        val found = recallRecords(
            records,
            tag = null,
            sourceId = "repositorio:dois",
            query = "folha",
            search = RecordingSearch(),
        )

        assertEquals(listOf("beta"), found.records.map { it.first.id })
    }

    @Test
    fun `texto que nao casa dentro de uma etiqueta que existe devolve vazio com os termos`() {
        val records = listOf(
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Cadastro de fornecedores", tags = listOf("cadastro")),
        )

        val found = recallRecords(records, tag = "folha", sourceId = null, query = "cadastro", search = RecordingSearch())

        assertTrue(found.records.isEmpty(), "não pode alcançar o registro de outra etiqueta")
        assertEquals(listOf("cadastro"), found.terms)
    }

    @Test
    fun `etiqueta inexistente nao chega ao motor`() {
        val search = RecordingSearch()

        val found = recallRecords(
            listOf(record("alfa", "Folha de pagamento", tags = listOf("folha"))),
            tag = "aeronave",
            sourceId = null,
            query = "folha",
            search = search,
        )

        assertTrue(found.records.isEmpty())
        assertTrue(search.seen.isEmpty(), "recorte vazio não tem o que pontuar")
    }

    @Test
    fun `a etiqueta casa sem diferenciar caixa`() {
        val found = recallRecords(
            listOf(record("alfa", "Folha de pagamento", tags = listOf("Folha"))),
            tag = "fOLHA",
            sourceId = null,
            query = null,
            search = RecordingSearch(),
        )

        assertEquals(listOf("alfa"), found.records.map { it.first.id })
    }

    @Test
    fun `sem consulta de texto o recorte sai na ordem em que veio, sem score e sem termos`() {
        val search = RecordingSearch()
        val records = listOf(
            record("zulu", "Folha de pagamento", tags = listOf("folha")),
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Cadastro", tags = listOf("cadastro")),
        )

        val found = recallRecords(records, tag = "folha", sourceId = null, query = "  ", search = search)

        assertEquals(listOf("zulu", "alfa"), found.records.map { it.first.id })
        assertTrue(found.records.all { it.second == null }, "sem consulta não há o que pontuar")
        assertNull(found.terms)
        assertTrue(search.seen.isEmpty(), "sem consulta o motor não é chamado")
    }

    @Test
    fun `a ordem e o score vem do motor`() {
        val search = RecordingSearch()
        val records = listOf(
            record("alfa", "Folha de pagamento"),
            record("beta", "Folha da folha"),
        )

        val found = recallRecords(records, tag = null, sourceId = null, query = "folha", search = search)

        assertEquals(listOf("beta", "alfa"), found.records.map { it.first.id })
        assertEquals(listOf(2f, 1f), found.records.map { it.second })
    }

    /**
     * Motor de mentira que registra o recorte recebido.
     *
     * Casa por ocorrência do termo no título e nas etiquetas, e pontua pelo número de ocorrências,
     * do mais forte para o mais fraco. Basta para provar a composição sem depender do motor real.
     */
    private class RecordingSearch : KnowledgeSearch {

        var seen: List<KnowledgeRecord> = emptyList()
            private set

        override fun search(records: List<KnowledgeRecord>, query: String): KnowledgeSearchResult {
            require(query.isNotBlank()) { "consulta em branco" }
            seen = records
            val term = query.trim().lowercase()
            val hits = records
                .map { it to occurrences(it, term) }
                .filter { (_, count) -> count > 0 }
                .sortedWith(compareByDescending<Pair<KnowledgeRecord, Int>> { it.second }.thenBy { it.first.id })
                .map { (record, count) -> KnowledgeHit(record.id, count.toFloat()) }
            return KnowledgeSearchResult(terms = listOf(term), hits = hits)
        }

        private fun occurrences(record: KnowledgeRecord, term: String): Int =
            (record.title + " " + record.tags.joinToString(" "))
                .lowercase()
                .split(Regex("\\W+"))
                .count { it == term }
    }

    private fun record(
        id: String,
        title: String,
        tags: List<String> = emptyList(),
        sourceId: String = "repositorio:$id",
    ) = KnowledgeRecord(
        schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION,
        id = id,
        title = title,
        body = "Corpo neutro.",
        tags = tags,
        provenance = Provenance(
            sourceKind = SourceKind.REPOSITORY,
            sourceId = sourceId,
            stamp = SourceStamp(sizeBytes = 1, modifiedAtEpochMillis = 0, sha256 = "abc"),
        ),
        author = "teste",
        createdAt = "2026-09-07T00:00:00Z",
        updatedAt = "2026-09-07T00:00:00Z",
    )
}
