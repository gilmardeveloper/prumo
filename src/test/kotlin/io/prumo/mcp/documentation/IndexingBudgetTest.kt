package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * O teto de tempo da indexação.
 *
 * O que estes testes protegem é a promessa da descrição: a chamada não segura a resposta até o
 * acervo inteiro ficar pronto, e diz quanto ficou para a próxima.
 */
class IndexingBudgetTest {

    @Test
    fun `para de indexar quando o tempo acaba, e conta o que sobrou`() {
        val indexadas = mutableListOf<String>()
        var relogio = 0L

        val pendentes = indexWithinBudget(
            sources = listOf("um", "dois", "tres"),
            indexed = { false },
            index = { indexadas.add(it); relogio += 6 },
            elapsedNanos = { relogio },
            budgetNanos = 10,
        )

        assertEquals(listOf("um", "dois"), indexadas)
        assertEquals(1, pendentes)
    }

    @Test
    fun `fonte ja indexada nao gasta o tempo nem conta como pendente`() {
        var relogio = 0L
        val indexadas = mutableListOf<String>()

        val pendentes = indexWithinBudget(
            sources = listOf("pronta", "nova"),
            indexed = { it == "pronta" },
            index = { indexadas.add(it); relogio += 100 },
            elapsedNanos = { relogio },
            budgetNanos = 10,
        )

        assertEquals(listOf("nova"), indexadas)
        assertEquals(0, pendentes)
    }

    @Test
    fun `com tempo de sobra nada fica pendente`() {
        val pendentes = indexWithinBudget(
            sources = listOf("um", "dois"),
            indexed = { false },
            index = { },
            elapsedNanos = { 0 },
            budgetNanos = 1_000,
        )

        assertEquals(0, pendentes)
    }

    @Test
    fun `sem fonte nenhuma nao ha pendencia`() {
        assertEquals(
            0,
            indexWithinBudget<String>(emptyList(), { false }, { }, { 0 }, 10),
        )
    }
}
