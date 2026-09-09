package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A fusão das duas listas.
 *
 * O que estes testes protegem é o que a intercalação anterior não sabia fazer: dar vantagem a quem
 * as duas buscas apontam, e devolver sempre a mesma ordem para a mesma entrada.
 */
class RankFusionTest {

    /**
     * O caso simétrico — duas listas invertidas uma da outra — empata por construção, e não prova
     * nada. O que distingue a fusão por posição da intercalação é o item que vai bem nas duas contra
     * o que lidera uma e afunda na outra.
     */
    @Test
    fun `quem vai bem nas duas vence quem lidera uma e afunda na outra`() {
        val porVetor = listOf("a", "b", "c", "d", "e", "f")
        val porPalavra = listOf("f", "b", "e", "d", "c", "a")

        val fundido = fuseByRank(porVetor, porPalavra, { it })

        assertEquals("b", fundido.first(), "b é segundo nas duas; a lidera uma e é última na outra")
    }

    @Test
    fun `item repetido aparece uma vez so`() {
        val fundido = fuseByRank(listOf("a", "b"), listOf("b", "a"), { it })

        assertEquals(listOf("a", "b").sorted(), fundido.sorted())
        assertEquals(2, fundido.size)
    }

    @Test
    fun `lista vazia de um lado nao quebra nem reordena a outra`() {
        assertEquals(listOf("a", "b", "c"), fuseByRank(listOf("a", "b", "c"), emptyList(), { it }))
        assertEquals(listOf("x", "y"), fuseByRank(emptyList(), listOf("x", "y"), { it }))
        assertEquals(emptyList<String>(), fuseByRank(emptyList<String>(), emptyList(), { it }))
    }

    @Test
    fun `a mesma entrada devolve sempre a mesma ordem`() {
        val ordens = (1..5).map { fuseByRank(listOf("a", "b", "c"), listOf("c", "a", "b"), { it }) }

        assertEquals(1, ordens.distinct().size, "ordens diferentes entre chamadas: $ordens")
    }

    @Test
    fun `a identidade decide o que e o mesmo item`() {
        val porVetor = listOf("mos|3" to "primeiro")
        val porPalavra = listOf("mos|3" to "segundo")

        val fundido = fuseByRank(porVetor, porPalavra, { it.first })

        assertEquals(1, fundido.size, "o mesmo trecho apareceu duas vezes")
        assertEquals("primeiro", fundido.single().second, "a primeira aparição é a que fica")
    }

    @Test
    fun `amortecimento sem sentido e recusado`() {
        assertThrows<IllegalArgumentException> { fuseByRank(listOf("a"), listOf("a"), { it }, k = 0) }
    }
}
