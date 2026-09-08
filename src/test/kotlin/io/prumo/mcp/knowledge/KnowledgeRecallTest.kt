package io.prumo.mcp.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A composição entre o alcance, os recortes exatos e a busca por relevância.
 *
 * O que estes testes protegem é a ordem: nem o recorte exato nem o motor chegam a ver o que está
 * fora de alcance, e o motor pontua apenas o que a etiqueta e a origem deixaram passar. Qualquer
 * inversão devolve resultado de aparência normal, com score e contagens medidos sobre registros que
 * o cliente não pediu ou não podia ver.
 */
class KnowledgeRecallTest {

    @Test
    fun `o motor so enxerga o que a etiqueta deixou passar`() {
        val search = RecordingSearch()
        val records = listOf(
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Folha de pagamento", tags = listOf("cadastro")),
        )

        recallRecords(records, "folha", null, "folha", search, AO_ALCANCE)

        assertEquals(listOf("alfa"), search.seen.map { it.id })
    }

    @Test
    fun `o motor so enxerga o que a origem deixou passar`() {
        val search = RecordingSearch()
        val records = listOf(
            record("alfa", "Folha de pagamento", sourceId = "repositorio:um"),
            record("beta", "Folha de pagamento", sourceId = "repositorio:dois"),
        )

        recallRecords(records, null, "repositorio:dois", "folha", search, AO_ALCANCE)

        assertEquals(listOf("beta"), search.seen.map { it.id })
    }

    @Test
    fun `texto com etiqueta devolve so o registro da etiqueta`() {
        val records = listOf(
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Folha de pagamento", tags = listOf("cadastro")),
        )

        val found = recallRecords(records, "folha", null, "folha", RecordingSearch(), AO_ALCANCE)

        assertEquals(listOf("alfa"), found.records.map { it.first.id })
    }

    @Test
    fun `texto com origem devolve so o registro daquela origem`() {
        val records = listOf(
            record("alfa", "Folha de pagamento", sourceId = "repositorio:um"),
            record("beta", "Folha de pagamento", sourceId = "repositorio:dois"),
        )

        val found = recallRecords(records, null, "repositorio:dois", "folha", RecordingSearch(), AO_ALCANCE)

        assertEquals(listOf("beta"), found.records.map { it.first.id })
    }

    @Test
    fun `texto que nao casa dentro de uma etiqueta que existe devolve vazio com os termos`() {
        val records = listOf(
            record("alfa", "Folha de pagamento", tags = listOf("folha")),
            record("beta", "Cadastro de fornecedores", tags = listOf("cadastro")),
        )

        val found = recallRecords(records, "folha", null, "cadastro", RecordingSearch(), AO_ALCANCE)

        assertTrue(found.records.isEmpty(), "não pode alcançar o registro de outra etiqueta")
        assertEquals(listOf("cadastro"), found.terms)
    }

    @Test
    fun `etiqueta inexistente nao chega ao motor`() {
        val search = RecordingSearch()
        val records = listOf(record("alfa", "Folha de pagamento", tags = listOf("folha")))

        val found = recallRecords(records, "aeronave", null, "folha", search, AO_ALCANCE)

        assertTrue(found.records.isEmpty())
        assertTrue(search.seen.isEmpty(), "recorte vazio não tem o que pontuar")
    }

    @Test
    fun `a etiqueta casa sem diferenciar caixa`() {
        val records = listOf(record("alfa", "Folha de pagamento", tags = listOf("Folha")))

        val found = recallRecords(records, "fOLHA", null, null, RecordingSearch(), AO_ALCANCE)

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

        val found = recallRecords(records, "folha", null, "  ", search, AO_ALCANCE)

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

        val found = recallRecords(records, null, null, "folha", search, AO_ALCANCE)

        assertEquals(listOf("beta", "alfa"), found.records.map { it.first.id })
        assertEquals(listOf(2f, 1f), found.records.map { it.second })
    }

    @Test
    fun `registro fora de alcance nao chega ao motor nem a lista`() {
        val search = RecordingSearch()
        val records = listOf(
            record("visivel", "Folha de pagamento"),
            record("excluido", "Folha de pagamento"),
        )

        val found = recallRecords(records, null, null, "folha", search, foraDeAlcance("excluido"))

        assertEquals(listOf("visivel"), search.seen.map { it.id }, "o motor pontuou o que está fora de alcance")
        assertEquals(listOf("visivel"), found.records.map { it.first.id })
        assertEquals(1, found.outOfReachCount)
    }

    /**
     * A contagem por consulta seria um oráculo: bastaria variar o texto e observar o número para
     * descobrir quais palavras existem dentro do arquivo que o desenvolvedor excluiu.
     */
    @Test
    fun `a contagem de fora de alcance nao varia com a consulta`() {
        val records = listOf(
            record("visivel", "Cadastro de fornecedores"),
            record("excluido", "Folha de pagamento"),
        )
        val fora = foraDeAlcance("excluido")

        val comTermoQueCasa = recallRecords(records, null, null, "folha", RecordingSearch(), fora)
        val comTermoQueNaoCasa = recallRecords(records, null, null, "aeronave", RecordingSearch(), fora)
        val semConsulta = recallRecords(records, null, null, null, RecordingSearch(), fora)

        assertEquals(1, comTermoQueCasa.outOfReachCount)
        assertEquals(1, comTermoQueNaoCasa.outOfReachCount)
        assertEquals(1, semConsulta.outOfReachCount)
    }

    @Test
    fun `a contagem cobre a base inteira, e nao o recorte pedido`() {
        val records = listOf(
            record("visivel", "Folha de pagamento", tags = listOf("folha")),
            record("excluido-folha", "Folha de pagamento", tags = listOf("folha")),
            record("excluido-outro", "Cadastro", tags = listOf("cadastro")),
        )

        val found = recallRecords(
            records,
            "folha",
            null,
            null,
            RecordingSearch(),
            foraDeAlcance("excluido-folha", "excluido-outro"),
        )

        assertEquals(listOf("visivel"), found.records.map { it.first.id })
        assertEquals(2, found.outOfReachCount, "a contagem encolheu com a etiqueta pedida")
    }

    @Test
    fun `a etiqueta pedida nao traz de volta o que esta fora de alcance`() {
        val search = RecordingSearch()
        val records = listOf(record("excluido", "Folha de pagamento", tags = listOf("folha")))

        val found = recallRecords(records, "folha", null, "folha", search, foraDeAlcance("excluido"))

        assertTrue(found.records.isEmpty(), "a etiqueta pedida trouxe de volta o que está fora de alcance")
        assertTrue(search.seen.isEmpty())
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

    private fun foraDeAlcance(vararg ids: String): (KnowledgeRecord) -> Boolean = { it.id in ids }

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

    private companion object {
        /** Base em que o desenvolvedor não excluiu nada: o alcance não é o assunto do teste. */
        val AO_ALCANCE: (KnowledgeRecord) -> Boolean = { false }
    }
}
