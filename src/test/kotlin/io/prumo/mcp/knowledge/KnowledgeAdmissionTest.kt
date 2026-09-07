package io.prumo.mcp.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No fluxo dos pacotes, quem lê o texto antes de aceitar é o desenvolvedor. Aqui a IA grava sozinha,
 * e estas regras são tudo o que existe no lugar dele.
 */
class KnowledgeAdmissionTest {

    private val context = AdmissionContext(
        documentationIds = setOf("eventos-esocial", "mos-s-1-3"),
        repositoryIds = setOf("folha-esocial", "folha"),
        existingRecords = 3,
    )

    @Test
    fun `registro derivado de fonte do workspace e aceito`() {
        assertTrue(KnowledgeAdmission.evaluate(record(), context).accepted)
    }

    @Test
    fun `repositorio tambem serve de fonte`() {
        val doRepositorio = record().copy(
            provenance = record().provenance.copy(
                sourceKind = SourceKind.REPOSITORY,
                sourceId = "folha-esocial",
            ),
        )

        assertTrue(KnowledgeAdmission.evaluate(doRepositorio, context).accepted)
    }

    /**
     * A regra que sustenta a escrita sem consentimento: conhecimento que não deriva do que já estava
     * ao alcance seria conteúdo trazido de fora, e isso é o que o pacote cobre.
     */
    @Test
    fun `fonte que nao pertence ao workspace e recusada, com o motivo`() {
        val deFora = record().copy(
            provenance = record().provenance.copy(sourceId = "documento-de-outro-lugar"),
        )

        val veredicto = KnowledgeAdmission.evaluate(deFora, context)

        assertFalse(veredicto.accepted)
        assertTrue((veredicto as Admission.Rejected).reason.contains("already in reach"))
    }

    @Test
    fun `fonte existe, mas na familia errada`() {
        val trocado = record().copy(
            provenance = record().provenance.copy(
                sourceKind = SourceKind.REPOSITORY,
                sourceId = "eventos-esocial",
            ),
        )

        assertFalse(KnowledgeAdmission.evaluate(trocado, context).accepted)
    }

    @Test
    fun `corpo vazio e recusado porque seria invisivel a qualquer busca`() {
        listOf("", "   ", "\n\t").forEach { vazio ->
            val veredicto = KnowledgeAdmission.evaluate(record().copy(body = vazio), context)

            assertFalse(veredicto.accepted, "corpo [$vazio]")
            assertTrue((veredicto as Admission.Rejected).reason.contains("body is empty"))
        }
    }

    @Test
    fun `titulo vazio e recusado`() {
        assertFalse(KnowledgeAdmission.evaluate(record().copy(title = "  "), context).accepted)
    }

    @Test
    fun `sem carimbo da fonte nao ha como responder frescor, entao nao entra`() {
        val semCarimbo = record().copy(
            provenance = record().provenance.copy(
                stamp = record().provenance.stamp.copy(sha256 = ""),
            ),
        )

        val veredicto = KnowledgeAdmission.evaluate(semCarimbo, context)

        assertFalse(veredicto.accepted)
        assertTrue((veredicto as Admission.Rejected).reason.contains("freshness"))
    }

    /**
     * Um agente cego gravou uma citação de linha -500 a -100 e outra da linha 900.000 num arquivo de
     * três mil bytes. A primeira é impossível e agora é recusada; a segunda continua passando, e
     * está declarada como limite: conferir exigiria contar as linhas do arquivo a cada gravação.
     */
    @Test
    fun `linha abaixo de um e recusada`() {
        listOf(-500 to -100, 0 to 10, 1 to 0, -1 to null).forEach { (primeira, ultima) ->
            val fora = record().copy(
                provenance = record().provenance.copy(firstLine = primeira, lastLine = ultima),
            )

            assertFalse(KnowledgeAdmission.evaluate(fora, context).accepted, "linhas $primeira..$ultima")
        }

        assertTrue(
            KnowledgeAdmission.evaluate(
                record().copy(provenance = record().provenance.copy(firstLine = 1, lastLine = 1)),
                context,
            ).accepted,
            "a primeira linha do arquivo é uma citação válida",
        )
    }

    @Test
    fun `intervalo de linhas invertido e recusado`() {
        val invertido = record().copy(
            provenance = record().provenance.copy(firstLine = 96, lastLine = 40),
        )

        assertFalse(KnowledgeAdmission.evaluate(invertido, context).accepted)
    }

    @Test
    fun `os tetos de tamanho valem, e o limite exato ainda passa`() {
        val corpoNoLimite = record().copy(body = "x".repeat(KnowledgeAdmission.MAX_BODY_LENGTH))
        val corpoAcima = record().copy(body = "x".repeat(KnowledgeAdmission.MAX_BODY_LENGTH + 1))
        val tituloNoLimite = record().copy(title = "t".repeat(KnowledgeAdmission.MAX_TITLE_LENGTH))
        val tituloAcima = record().copy(title = "t".repeat(KnowledgeAdmission.MAX_TITLE_LENGTH + 1))

        assertTrue(KnowledgeAdmission.evaluate(corpoNoLimite, context).accepted, "corpo no limite")
        assertFalse(KnowledgeAdmission.evaluate(corpoAcima, context).accepted, "corpo acima")
        assertTrue(KnowledgeAdmission.evaluate(tituloNoLimite, context).accepted, "título no limite")
        assertFalse(KnowledgeAdmission.evaluate(tituloAcima, context).accepted, "título acima")
    }

    @Test
    fun `etiqueta demais, etiqueta vazia e etiqueta longa demais sao recusadas`() {
        val muitas = record().copy(tags = List(KnowledgeAdmission.MAX_TAGS + 1) { "t$it" })
        val vazia = record().copy(tags = listOf("esocial", " "))
        val longa = record().copy(tags = listOf("t".repeat(KnowledgeAdmission.MAX_TAG_LENGTH + 1)))

        assertFalse(KnowledgeAdmission.evaluate(muitas, context).accepted, "muitas")
        assertFalse(KnowledgeAdmission.evaluate(vazia, context).accepted, "vazia")
        assertFalse(KnowledgeAdmission.evaluate(longa, context).accepted, "longa")
        assertTrue(
            KnowledgeAdmission.evaluate(record().copy(tags = List(KnowledgeAdmission.MAX_TAGS) { "t$it" }), context)
                .accepted,
            "a quantidade exata do limite ainda passa",
        )
    }

    @Test
    fun `a base tem teto, e ele e conferido antes de gravar`() {
        val cheio = context.copy(existingRecords = KnowledgeAdmission.MAX_RECORDS_PER_WORKSPACE)

        val veredicto = KnowledgeAdmission.evaluate(record(), cheio)

        assertFalse(veredicto.accepted)
        assertTrue((veredicto as Admission.Rejected).reason.contains("remove some first"))
    }

    @Test
    fun `identificador fora do padrao da casa e recusado, porque vira chave`() {
        listOf("", "com espaço", "com/barra", "com..ponto", "x".repeat(65)).forEach { id ->
            assertFalse(KnowledgeAdmission.evaluate(record().copy(id = id), context).accepted, "id [$id]")
        }
    }

    @Test
    fun `formato desconhecido nao entra`() {
        val doFuturo = record().copy(schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION + 1)

        assertFalse(KnowledgeAdmission.evaluate(doFuturo, context).accepted)
    }

    @Test
    fun `toda recusa diz o que o Prumo fez e por que`() {
        val veredicto = KnowledgeAdmission.evaluate(record().copy(body = ""), context)

        val reason = (veredicto as Admission.Rejected).reason
        assertTrue(reason.startsWith("Prumo did not store this record:"), "não diz o que aconteceu")
        assertEquals('.', reason.last(), "a recusa não termina em frase completa")
    }

    private fun record() = KnowledgeRecord(
        schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION,
        id = "s-1210-prazo",
        title = "Prazo de entrega do S-1210",
        body = "O S-1210 tem prazo até o dia 15 do mês seguinte ao do pagamento.",
        tags = listOf("esocial", "prazo"),
        provenance = Provenance(
            sourceKind = SourceKind.DOCUMENTATION,
            sourceId = "eventos-esocial",
            path = "S-1210.md",
            firstLine = 40,
            lastLine = 96,
            stamp = SourceStamp(65_557, 1_755_500_000_000, "sha256:abc123"),
        ),
        author = "claude-code/2.1",
        createdAt = "2026-09-07T12:00:00Z",
        updatedAt = "2026-09-07T12:00:00Z",
    )
}
