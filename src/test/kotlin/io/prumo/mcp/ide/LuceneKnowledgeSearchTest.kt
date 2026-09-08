package io.prumo.mcp.ide

import io.prumo.mcp.knowledge.KnowledgeRecord
import io.prumo.mcp.knowledge.Provenance
import io.prumo.mcp.knowledge.SourceKind
import io.prumo.mcp.knowledge.SourceStamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A busca por relevância sobre a memória.
 *
 * O que estes testes protegem é o que a busca antiga não fazia: achar por flexão diferente da
 * escrita, olhar o corpo e não só o título, e ordenar por força do casamento.
 */
class LuceneKnowledgeSearchTest {

    private val search = LuceneKnowledgeSearch()

    @Test
    fun `flexao diferente do portugues casa o mesmo registro`() {
        val records = listOf(
            record("folha", "Processamento de pagamentos da folha", "Roda todo dia 20."),
            record("cadastro", "Cadastro de fornecedores", "Nada a ver com o assunto."),
        )

        val result = search.search(records, "pagamento")

        assertEquals(listOf("folha"), result.hits.map { it.id })
    }

    @Test
    fun `flexao diferente do ingles casa o mesmo registro`() {
        val records = listOf(
            record("payroll", "Payroll payments processing", "Runs on the twentieth."),
            record("vendors", "Vendor registration", "Unrelated subject."),
        )

        val result = search.search(records, "payment")

        assertEquals(listOf("payroll"), result.hits.map { it.id })
    }

    @Test
    fun `o corpo entra na busca, e nao so o titulo`() {
        val records = listOf(
            record("evento", "Regras de negócio do módulo", "O evento S1200 leva as rubricas do mês."),
        )

        assertEquals(listOf("evento"), search.search(records, "rubricas").hits.map { it.id })
    }

    @Test
    fun `o titulo pesa mais que o corpo`() {
        val records = listOf(
            record("corpo", "Assunto qualquer", "Aqui se fala de rubrica uma vez."),
            record("titulo", "Rubrica do evento", "Texto sem o termo procurado."),
        )

        val hits = search.search(records, "rubrica").hits

        assertEquals(listOf("titulo", "corpo"), hits.map { it.id })
        assertTrue(hits.first().score > hits.last().score, "score do título: ${hits.map { it.score }}")
    }

    @Test
    fun `a etiqueta e procurada junto com o titulo e o corpo`() {
        val records = listOf(
            record("etiqueta", "Assunto qualquer", "Texto neutro.", tags = listOf("folha")),
            record("nenhum", "Outro assunto", "Outro texto."),
        )

        assertEquals(listOf("etiqueta"), search.search(records, "folha").hits.map { it.id })
    }

    @Test
    fun `a consulta devolve os termos em que foi reduzida`() {
        val result = search.search(listOf(record("x", "Título", "Corpo")), "pagamentos")

        assertTrue(result.terms.contains("pagament"), "termos: ${result.terms}")
    }

    @Test
    fun `palavra vazia de um idioma nao casa pelo campo do outro`() {
        val records = listOf(record("x", "Folha de pagamento", "Corpo qualquer"))

        assertTrue(search.search(records, "de").hits.isEmpty(), "\"de\" não pode casar nada")
        assertTrue(search.search(records, "the").hits.isEmpty(), "\"the\" não pode casar nada")
    }

    @Test
    fun `consulta que vira nada devolve os termos vazios e nenhum resultado`() {
        val result = search.search(listOf(record("x", "Pagamento", "Corpo")), "de o a")

        assertTrue(result.terms.isEmpty(), "termos: ${result.terms}")
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `base vazia responde sem quebrar`() {
        val result = search.search(emptyList(), "pagamento")

        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `consulta em branco e recusada`() {
        listOf("", "   ").forEach { vazia ->
            assertThrows<IllegalArgumentException> { search.search(listOf(record("x", "T", "C")), vazia) }
        }
    }

    @Test
    fun `termo que nao existe na base nao casa nada`() {
        val records = listOf(record("folha", "Processamento de pagamentos", "Roda todo dia 20."))

        assertTrue(search.search(records, "aeronave").hits.isEmpty())
    }

    @Test
    fun `empate no score sai sempre na mesma ordem`() {
        val records = listOf(
            record("zulu", "Folha de pagamento", "Mesmo texto."),
            record("alfa", "Folha de pagamento", "Mesmo texto."),
            record("mike", "Folha de pagamento", "Mesmo texto."),
        )

        val ordens = (1..5).map { search.search(records.shuffled(), "folha").hits.map { hit -> hit.id } }

        assertEquals(listOf("alfa", "mike", "zulu"), ordens.first())
        assertEquals(1, ordens.distinct().size, "ordens diferentes entre chamadas: $ordens")
    }

    @Test
    fun `o score acompanha cada resultado e decresce ao longo da lista`() {
        val records = listOf(
            record("fraco", "Assunto qualquer", "Cita folha uma vez em meio a muito outro texto aqui."),
            record("forte", "Folha de pagamento da folha", "Folha outra vez."),
        )

        val hits = search.search(records, "folha").hits

        assertEquals(listOf("forte", "fraco"), hits.map { it.id })
        assertTrue(hits.first().score > hits.last().score, "scores: ${hits.map { it.score }}")
        assertTrue(hits.all { it.score > 0f }, "score precisa ser positivo: ${hits.map { it.score }}")
    }

    private fun record(
        id: String,
        title: String,
        body: String,
        tags: List<String> = emptyList(),
    ) = KnowledgeRecord(
        schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION,
        id = id,
        title = title,
        body = body,
        tags = tags,
        provenance = Provenance(
            sourceKind = SourceKind.REPOSITORY,
            sourceId = "repositorio:$id",
            stamp = SourceStamp(sizeBytes = 1, modifiedAtEpochMillis = 0, sha256 = "abc"),
        ),
        author = "teste",
        createdAt = "2026-09-07T00:00:00Z",
        updatedAt = "2026-09-07T00:00:00Z",
    )
}
