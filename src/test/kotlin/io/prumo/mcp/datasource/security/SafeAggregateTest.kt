package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Contar quantas pessoas têm CPF preenchido é resposta de qualidade de cadastro, não dado pessoal.
 *
 * Esconder o resultado inutiliza a análise sem proteger ninguém — e, quando a coluna é obrigatória,
 * o número é dedutível de qualquer forma. A separação é entre agregação que produz um número sobre o
 * conjunto e agregação que devolve um valor de origem.
 */
@Tag("security")
class SafeAggregateTest {

    @Test
    fun `contagem sobre coluna pessoal e liberada`() {
        val sql = "SELECT count(num_cpf) AS com_cpf FROM db_sgp.tb_pessoa"

        assertEquals(setOf(1), SensitiveColumnScanner.safeAggregatePositions(sql, 1))
    }

    @Test
    fun `soma, media e comprimento tambem sao liberados`() {
        val sql = "SELECT sum(vlr) AS a, avg(vlr) AS b, length(num_cpf) AS c FROM t"

        assertEquals(setOf(1, 2, 3), SensitiveColumnScanner.safeAggregatePositions(sql, 3))
    }

    @Test
    fun `max e min devolvem valor de origem e seguem protegidos`() {
        val sql = "SELECT max(num_cpf) AS a, min(txt_email) AS b FROM db_sgp.tb_pessoa"

        assertTrue(SensitiveColumnScanner.safeAggregatePositions(sql, 2).isEmpty())
    }

    @Test
    fun `string_agg e array_agg seguem protegidos`() {
        val sql = "SELECT string_agg(txt_email, ',') AS a, array_agg(num_cpf) AS b FROM db_sgp.tb_pessoa"

        assertTrue(SensitiveColumnScanner.safeAggregatePositions(sql, 2).isEmpty())
    }

    @Test
    fun `agregacao segura misturada com funcao que devolve valor nao e liberada`() {
        val sql = "SELECT count(num_cpf) || max(num_cpf) AS a FROM db_sgp.tb_pessoa"

        assertTrue(SensitiveColumnScanner.safeAggregatePositions(sql, 1).isEmpty())
    }

    @Test
    fun `a coluna projetada ao lado da contagem continua protegida`() {
        val sql = "SELECT num_cpf AS a, count(*) AS b FROM db_sgp.tb_pessoa GROUP BY num_cpf"

        assertEquals(setOf(2), SensitiveColumnScanner.safeAggregatePositions(sql, 2))
    }

    @Test
    fun `lista de selecao nao mapeavel nao libera nada`() {
        assertTrue(SensitiveColumnScanner.safeAggregatePositions("SELECT * FROM t", 3).isEmpty())
        assertTrue(
            SensitiveColumnScanner.safeAggregatePositions("WITH x AS (SELECT 1) SELECT count(*) FROM x", 1)
                .isEmpty(),
        )
    }
}
