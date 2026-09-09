package io.prumo.mcp.ide

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Sonda de classpath do motor de inferência, no mesmo molde das que existem para o MVStore e para o
 * PDFBox.
 *
 * O que ela responde é se as duas bibliotecas carregam dentro do ambiente do plugin — o runtime, que
 * extrai binário nativo, e o tokenizador, que também. Falha aqui derruba a busca por sentido antes
 * de qualquer código de produto depender dela.
 */
class EmbeddingRuntimeTest {

    @Test
    fun `o motor de inferencia carrega`() {
        assertTrue(EmbeddingRuntime.available, EmbeddingRuntime.unavailableReason.orEmpty())
        assertNull(EmbeddingRuntime.unavailableReason)
    }

    /**
     * O mesmo aprendizado da contagem de fora de alcance: campo booleano com padrão some da resposta
     * quando é falso, e some justamente no caso que se quer diagnosticar.
     */
    @Test
    fun `o diagnostico diz se o motor carregou mesmo quando ele nao carregou`() {
        val resposta = io.prumo.mcp.toolsets.PrumoDiagnostics(
            pluginVersion = "0.10.0",
            projectName = "folha",
            workspaceConfigured = true,
            embeddingRuntimeAvailable = false,
        )

        val serializado = kotlinx.serialization.json.Json.encodeToString(resposta)

        assertTrue(serializado.contains("\"embeddingRuntimeAvailable\":false"), serializado)
    }

    @Test
    fun `consultar duas vezes nao carrega duas vezes`() {
        val primeiro = EmbeddingRuntime.environmentOrNull()
        val segundo = EmbeddingRuntime.environmentOrNull()

        assertTrue(primeiro === segundo, "o ambiente foi recriado")
    }

    /**
     * O tokenizador desta sonda vem da rede, e é a única coisa aqui que depende dela: em máquina sem
     * acesso o teste se pula em vez de falhar. No produto o arquivo do tokenizador é baixado e
     * conferido pelo Prumo, como o modelo.
     */
    @Test
    fun `o tokenizador carrega e devolve os ids que a rede espera`() {
        val tokenizador = runCatching { HuggingFaceTokenizer.newInstance("intfloat/multilingual-e5-small") }
        assumeTrue(tokenizador.isSuccess, "sem acesso ao tokenizador nesta máquina")

        tokenizador.getOrThrow().use {
            val codificado = it.encode("rubrica com incidência suspensa")

            assertTrue(codificado.ids.size > 3, codificado.ids.toList().toString())
            assertTrue(codificado.attentionMask.all { mascara -> mascara == 1L })
        }
    }
}
