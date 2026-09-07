package io.prumo.mcp.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * O veredicto de frescor é a garantia central desta camada: é o que permite a uma IA decidir sozinha
 * entre o destilado e a fonte. Por isso ele é fail-closed — só é `FRESH` quando nada mudou.
 */
class ProvenanceTest {

    private val gravado = SourceStamp(
        sizeBytes = 65_557,
        modifiedAtEpochMillis = 1_755_500_000_000,
        sha256 = "sha256:abc123",
    )

    @Test
    fun `carimbo identico responde fresco`() {
        assertEquals(Freshness.FRESH, freshnessOf(gravado, gravado.copy()))
    }

    @Test
    fun `fonte ausente responde orfao, nunca velho`() {
        assertEquals(Freshness.ORPHAN, freshnessOf(gravado, null))
    }

    @Test
    fun `cada medida do carimbo, sozinha, ja torna o registro velho`() {
        val divergentes = mapOf(
            "tamanho" to gravado.copy(sizeBytes = gravado.sizeBytes + 1),
            "data" to gravado.copy(modifiedAtEpochMillis = gravado.modifiedAtEpochMillis + 1),
            "resumo" to gravado.copy(sha256 = "sha256:outro"),
        )

        divergentes.forEach { (medida, atual) ->
            assertEquals(Freshness.STALE, freshnessOf(gravado, atual), "divergência em $medida")
        }
    }

    /**
     * O caso que a escolha de carimbar o arquivo inteiro torna possível: alguém editou outra parte
     * do documento, o trecho citado continua igual, e mesmo assim o registro é marcado velho.
     * É deliberado — falso `STALE` custa uma redestilação, falso `FRESH` entrega conteúdo errado.
     */
    @Test
    fun `mudanca fora do trecho citado tambem marca o registro como velho`() {
        val atual = gravado.copy(sizeBytes = gravado.sizeBytes + 4_000, sha256 = "sha256:depois")

        assertEquals(Freshness.STALE, freshnessOf(gravado, atual))
    }

    @Test
    fun `a coordenada nao participa do veredicto`() {
        val comLinhas = Provenance(
            sourceKind = SourceKind.DOCUMENTATION,
            sourceId = "eventos-esocial",
            path = "S-1210.md",
            firstLine = 40,
            lastLine = 96,
            stamp = gravado,
        )
        val semLinhas = comLinhas.copy(firstLine = null, lastLine = null, path = null)

        assertEquals(
            freshnessOf(comLinhas.stamp, gravado),
            freshnessOf(semLinhas.stamp, gravado),
            "o intervalo de linhas mudou o veredicto",
        )
    }

    @Test
    fun `o formato corrente e aceito e qualquer outro e recusado`() {
        assertTrue(supportsSchema(KnowledgeRecord.CURRENT_SCHEMA_VERSION))
        assertFalse(supportsSchema(KnowledgeRecord.CURRENT_SCHEMA_VERSION + 1), "formato mais novo")
        assertFalse(supportsSchema(KnowledgeRecord.CURRENT_SCHEMA_VERSION - 1), "formato mais velho")
        assertFalse(supportsSchema(0))
    }

    @Test
    fun `a recusa de formato diz o que fazer, nao so o que houve`() {
        val falha = UnsupportedSchemaException(version = 7)

        assertTrue(falha.message!!.contains("7"), "não diz qual formato veio")
        assertTrue(
            falha.message!!.contains("Distil it again"),
            "não instrui a redestilar: a base é reconstruível, e a mensagem precisa dizer isso",
        )
    }
}
