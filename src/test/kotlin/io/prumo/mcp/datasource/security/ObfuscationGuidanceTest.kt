package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A orientação é texto afirmado ao cliente, e neste produto uma afirmação já divergiu do código.
 *
 * Os testes amarram as duas coisas: o texto nasce da lista que decide o comportamento, não repete o
 * que já é publicado por coluna, e não pode virar um roteiro de contorno.
 */
@Tag("security")
class ObfuscationGuidanceTest {

    @Test
    fun `o texto cita cada agregacao que o codigo devolve completa`() {
        val texto = ObfuscationGuidance.forObfuscatedResult()

        SensitiveColumnScanner.SAFE_AGGREGATE_NAMES.forEach { nome ->
            assertTrue(texto.contains(nome), "a orientação não cita '$nome', que o código libera")
        }
    }

    @Test
    fun `o texto nao cita agregacao que devolve valor de origem`() {
        val texto = ObfuscationGuidance.forObfuscatedResult() + ObfuscationGuidance.forDatasource(true)

        listOf("min", "max", "string_agg", "array_agg", "json_agg").forEach { nome ->
            assertFalse(texto.contains(nome), "a orientação cita '$nome', que não volta completa")
        }
    }

    /**
     * Dizer em que construções a proteção é mais restritiva entrega o caminho de contorno a quem não
     * o procurou. A orientação diz o que fazer, nunca o que evitar.
     */
    @Test
    fun `o texto nao ensina caminho de contorno`() {
        val textos = listOf(
            ObfuscationGuidance.forObfuscatedResult(),
            ObfuscationGuidance.forUnprotectedResult(),
            ObfuscationGuidance.forDatasource(true),
            ObfuscationGuidance.forDatasource(false),
        )

        // "as" sozinho é palavra comum do inglês; o que se recusa é a construção SQL e o vocabulário
        // que descreve a falha da proteção.
        val proibidos = listOf(
            "substr", "alias", " as codigo", "bypass", "avoid", "do not use", "cannot be masked",
            "fails", "workaround", "instead of masking", "does not protect",
        )
        textos.forEach { texto ->
            proibidos.forEach { termo ->
                assertFalse(
                    texto.lowercase().contains(termo),
                    "a orientação contém '$termo', que aponta caminho de contorno: $texto",
                )
            }
        }
    }

    @Test
    fun `o texto diz que valor escondido nao serve como chave`() {
        val texto = ObfuscationGuidance.forObfuscatedResult()

        assertTrue(texto.contains("not a key"), texto)
        assertTrue(texto.contains("join") || texto.contains("deduplicate"), texto)
    }

    @Test
    fun `o texto e curto o bastante para nao competir com o dado`() {
        listOf(
            ObfuscationGuidance.forObfuscatedResult(),
            ObfuscationGuidance.forUnprotectedResult(),
            ObfuscationGuidance.forDatasource(true),
            ObfuscationGuidance.forDatasource(false),
        ).forEach { texto ->
            assertTrue(texto.length <= MAX_LENGTH, "orientação com ${texto.length} caracteres: $texto")
        }
    }

    @Test
    fun `o aviso de protecao desligada e distinto do de protecao ligada`() {
        assertNotEquals(ObfuscationGuidance.forObfuscatedResult(), ObfuscationGuidance.forUnprotectedResult())
        assertNotEquals(ObfuscationGuidance.forDatasource(true), ObfuscationGuidance.forDatasource(false))
        assertTrue(ObfuscationGuidance.forUnprotectedResult().contains("turned off"))
    }

    @Test
    fun `a orientacao nao repete a categoria, que ja e publicada por coluna`() {
        val texto = ObfuscationGuidance.forObfuscatedResult()

        listOf("CPF", "EMAIL", "NAME", "BIRTH_DATE", "obfuscatedAs").forEach { termo ->
            assertFalse(texto.contains(termo), "a orientação repete '$termo', que já vem por coluna")
        }
    }

    private companion object {
        const val MAX_LENGTH = 300
    }
}
