package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * O mapeamento de item de seleção para posição de saída existe para que uma agregação inocente não
 * seja apagada só porque a consulta menciona segredo em outro item.
 *
 * Todo caso em que o mapeamento não é confiável devolve `null`, e quem chama volta a mascarar toda
 * coluna calculada.
 */
@Tag("security")
class SensitiveColumnScannerTest {

    private val masking = DataMaskingPolicy.NONE

    @Test
    fun `a agregacao inocente nao e contaminada pelo segredo ao lado`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT count(*) AS total, max(txt_senha) AS x FROM db.tb_usuario",
            masking,
            columnCount = 2,
        )

        assertEquals(setOf(2), posicoes)
    }

    @Test
    fun `funcao sobre segredo continua sensivel`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT length(txt_senha) AS n FROM db.tb_usuario",
            masking,
            columnCount = 1,
        )

        assertEquals(setOf(1), posicoes)
    }

    @Test
    fun `rotulo que anuncia segredo e sensivel mesmo sem coluna de origem`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions("SELECT 1 AS senha, 2 AS normal", masking, 2)

        assertEquals(setOf(1), posicoes)
    }

    @Test
    fun `expansao por asterisco impede o mapeamento`() {
        assertNull(SensitiveColumnScanner.sensitivePositions("SELECT * FROM db.tb_usuario", masking, 3))
        assertNull(SensitiveColumnScanner.sensitivePositions("SELECT u.* FROM db.tb_usuario u", masking, 3))
    }

    @Test
    fun `contagem divergente entre itens e colunas impede o mapeamento`() {
        assertNull(SensitiveColumnScanner.sensitivePositions("SELECT a, b FROM t", masking, columnCount = 3))
    }

    @Test
    fun `statement que nao e um select simples impede o mapeamento`() {
        assertNull(SensitiveColumnScanner.sensitivePositions("WITH x AS (SELECT 1) SELECT * FROM x", masking, 1))
        assertNull(SensitiveColumnScanner.sensitivePositions("EXPLAIN SELECT txt_senha FROM t", masking, 1))
    }

    @Test
    fun `virgula dentro de funcao nao divide item`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT coalesce(a, b) AS total, coalesce(txt_senha, 'x') AS y FROM t",
            masking,
            columnCount = 2,
        )

        assertEquals(setOf(2), posicoes)
    }

    @Test
    fun `virgula dentro de literal nao divide item`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT 'a, b' AS rotulo, txt_senha AS s FROM t",
            masking,
            columnCount = 2,
        )

        assertEquals(setOf(2), posicoes)
    }

    @Test
    fun `subconsulta com segredo contamina apenas o proprio item`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT count(*) AS total, (SELECT max(txt_senha) FROM t2) AS s FROM t1",
            masking,
            columnCount = 2,
        )

        assertEquals(setOf(2), posicoes)
    }

    @Test
    fun `a lista de selecao termina na palavra que a encerra`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT count(*) AS total FROM t WHERE txt_senha IS NOT NULL",
            masking,
            columnCount = 1,
        )

        assertTrue(posicoes.orEmpty().isEmpty(), "o predicado não é item de seleção: $posicoes")
    }

    @Test
    fun `distinct nao atrapalha o mapeamento`() {
        val posicoes = SensitiveColumnScanner.sensitivePositions(
            "SELECT DISTINCT nome, txt_senha FROM t",
            masking,
            columnCount = 2,
        )

        assertEquals(setOf(2), posicoes)
    }

    @Test
    fun `a varredura por statement inteiro continua valendo para quem depende dela`() {
        assertTrue(SensitiveColumnScanner.touchesSensitiveColumn("SELECT length(txt_senha) FROM t", masking))
        assertTrue(!SensitiveColumnScanner.touchesSensitiveColumn("SELECT count(*) FROM t", masking))
    }
}
