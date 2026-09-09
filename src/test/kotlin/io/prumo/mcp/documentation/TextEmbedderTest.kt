package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * As duas contas puras do embutidor.
 *
 * A média por token e a normalização decidem o que "próximo" significa para a busca. Somar o
 * enchimento junto aproximaria todos os trechos entre si, e vetor sem comprimento 1 faria o cosseno
 * do índice comparar tamanho em vez de direção.
 */
class TextEmbedderTest {

    @Test
    fun `a media ignora o enchimento`() {
        val tokens = listOf(
            floatArrayOf(2f, 0f),
            floatArrayOf(4f, 0f),
            floatArrayOf(100f, 100f),
        )

        val media = meanPool(tokens, longArrayOf(1, 1, 0))

        assertEquals(3f, media[0])
        assertEquals(0f, media[1])
    }

    @Test
    fun `sequencia so de enchimento nao vira divisao por zero`() {
        val media = meanPool(listOf(floatArrayOf(5f, 5f)), longArrayOf(0))

        assertEquals(listOf(0f, 0f), media.toList())
    }

    @Test
    fun `media sem token e recusada, e mascara de tamanho errado tambem`() {
        assertThrows<IllegalArgumentException> { meanPool(emptyList(), longArrayOf()) }
        assertThrows<IllegalArgumentException> { meanPool(listOf(floatArrayOf(1f)), longArrayOf(1, 1)) }
    }

    @Test
    fun `o vetor normalizado tem comprimento um`() {
        val normalizado = l2Normalize(floatArrayOf(3f, 4f))

        assertEquals(0.6f, normalizado[0], 1e-6f)
        assertEquals(0.8f, normalizado[1], 1e-6f)
        assertEquals(1f, comprimento(normalizado), 1e-6f)
    }

    @Test
    fun `vetor nulo nao vira NaN`() {
        val normalizado = l2Normalize(floatArrayOf(0f, 0f))

        assertTrue(normalizado.all { it == 0f }, normalizado.toList().toString())
    }

    @Test
    fun `normalizar preserva a direcao, e portanto a ordem por cosseno`() {
        val curto = l2Normalize(floatArrayOf(1f, 1f))
        val longo = l2Normalize(floatArrayOf(10f, 10f))

        assertTrue(abs(curto[0] - longo[0]) < 1e-6f, "a direção mudou com o tamanho")
    }

    private fun comprimento(vetor: FloatArray) =
        sqrt(vetor.fold(0.0) { soma, valor -> soma + valor * valor }).toFloat()
}
