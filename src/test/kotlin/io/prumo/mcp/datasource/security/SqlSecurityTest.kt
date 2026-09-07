package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Corpus de SQL aceito e recusado.
 *
 * Cada caso recusado aqui é uma forma conhecida de fazer escrita passar por leitura. O teste existe
 * para que acrescentar um caminho novo exija encarar a lista.
 */
@Tag("security")
class SqlStatementClassifierTest {

    @Test
    fun `consulta simples e aceita`() {
        assertTrue(SqlStatementClassifier.classify("SELECT 1").allowed)
        assertTrue(SqlStatementClassifier.classify("SELECT nome FROM folha.servidor WHERE id = 1").allowed)
    }

    @Test
    fun `ponto e virgula dentro de literal nao vira segundo statement`() {
        val classification = SqlStatementClassifier.classify(
            "SELECT * FROM servidor WHERE nome = 'silva; DROP TABLE servidor'",
        )

        assertTrue(classification.allowed, "o ponto e virgula esta dentro do texto, nao no SQL")
    }

    @Test
    fun `comentario antes da consulta nao atrapalha`() {
        assertTrue(SqlStatementClassifier.classify("-- relatorio mensal\nSELECT 1").allowed)
        assertTrue(SqlStatementClassifier.classify("/* bloco */ SELECT 1").allowed)
    }

    @Test
    fun `WITH de leitura e aceito e union tambem`() {
        assertTrue(
            SqlStatementClassifier.classify(
                "WITH ativos AS (SELECT * FROM servidor WHERE ativo) SELECT count(*) FROM ativos",
            ).allowed,
        )
        assertTrue(SqlStatementClassifier.classify("SELECT 1 UNION SELECT 2").allowed)
    }

    @Test
    fun `EXPLAIN sem ANALYZE e aceito`() {
        val classification = SqlStatementClassifier.classify("EXPLAIN SELECT * FROM servidor")

        assertTrue(classification.allowed)
        assertEquals(SqlStatementType.EXPLAIN, classification.type)
    }

    @Test
    fun `EXPLAIN ANALYZE e recusado porque executa de verdade`() {
        val classification = SqlStatementClassifier.classify("EXPLAIN ANALYZE SELECT * FROM servidor")

        assertFalse(classification.allowed)
        assertEquals(SqlStatementType.WRITE, classification.type)
    }

    @Test
    fun `escrita declarada e recusada`() {
        listOf(
            "INSERT INTO servidor (nome) VALUES ('x')",
            "UPDATE servidor SET nome = 'x'",
            "DELETE FROM servidor",
            "DROP TABLE servidor",
            "TRUNCATE TABLE servidor",
            "ALTER TABLE servidor ADD COLUMN x int",
            "CREATE TABLE nova (id int)",
            "GRANT SELECT ON servidor TO leitor",
        ).forEach { sql ->
            val classification = SqlStatementClassifier.classify(sql)
            assertFalse(classification.allowed, "deveria recusar: $sql")
        }
    }

    @Test
    fun `dois statements na mesma chamada sao recusados`() {
        val classification = SqlStatementClassifier.classify("SELECT 1; DROP TABLE servidor")

        assertFalse(classification.allowed)
        assertEquals(SqlStatementType.MULTIPLE, classification.type)
    }

    @Test
    fun `CTE que escreve e recusada mesmo terminando em SELECT`() {
        val classification = SqlStatementClassifier.classify(
            "WITH removidos AS (DELETE FROM servidor WHERE ativo = false RETURNING *) SELECT * FROM removidos",
        )

        assertFalse(classification.allowed, "a escrita esta dentro do WITH")
    }

    @Test
    fun `SELECT INTO cria tabela e e recusado`() {
        val classification = SqlStatementClassifier.classify("SELECT * INTO copia FROM servidor")

        assertFalse(classification.allowed)
        assertEquals(SqlStatementType.WRITE, classification.type)
    }

    @Test
    fun `o que o parser nao entende e recusado, nao aceito na duvida`() {
        listOf("", "   ", "isto nao e sql", "COPY servidor FROM '/etc/passwd'", "CALL rotina()").forEach { sql ->
            assertFalse(SqlStatementClassifier.classify(sql).allowed, "deveria recusar: '$sql'")
        }
    }

    @Test
    fun `a recusa explica o motivo sem repetir o SQL`() {
        val classification = SqlStatementClassifier.classify("DELETE FROM servidor WHERE cpf = '12345678900'")

        val denied = classification as SqlClassification.Denied
        assertTrue(denied.reason.isNotBlank())
        assertFalse(denied.reason.contains("12345678900"), "o motivo nao pode repetir o dado da consulta")
    }
}

@Tag("security")
class DataMaskingPolicyTest {

    @Test
    fun `coluna com cara de segredo e mascarada sem configuracao alguma`() {
        val policy = DataMaskingPolicy.NONE

        listOf("password", "senha", "senha_atual", "user_password", "api_key_hash", "access_token")
            .forEach { assertEquals(MaskingRule.MASK, policy.ruleFor(it), it) }
    }

    @Test
    fun `coluna comum passa`() {
        assertEquals(MaskingRule.ALLOW, DataMaskingPolicy.NONE.ruleFor("nome"))
        assertEquals(MaskingRule.ALLOW, DataMaskingPolicy.NONE.ruleFor("valor_bruto"))
    }

    /**
     * O fragmento vale como segmento, não como pedaço de palavra. `secretaria` é lotação, e mascarar
     * a lotação inteira de um sistema de folha custa a análise que o produto existe para permitir.
     */
    @Test
    fun `palavra que contem o fragmento no meio nao e segredo`() {
        val policy = DataMaskingPolicy.NONE

        listOf("dsc_secretaria", "isn_secretaria", "cod_secretaria_origem", "txt_resenha")
            .forEach { assertEquals(MaskingRule.ALLOW, policy.ruleFor(it), it) }
    }

    @Test
    fun `o plural e a numeracao do fragmento continuam sendo segredo`() {
        assertEquals(MaskingRule.MASK, DataMaskingPolicy.NONE.ruleFor("txt_senhas"))
        assertEquals(MaskingRule.MASK, DataMaskingPolicy.NONE.ruleFor("access_tokens"))
        assertEquals(MaskingRule.MASK, DataMaskingPolicy.NONE.ruleFor("txt_senha1"))
        assertEquals(MaskingRule.MASK, DataMaskingPolicy.NONE.ruleFor("password2"))
    }

    @Test
    fun `coluna marcada pelo usuario tambem e mascarada`() {
        val policy = DataMaskingPolicy(mapOf("cpf" to MaskingRule.MASK))

        assertEquals(MaskingRule.MASK, policy.ruleFor("CPF"))
        assertEquals("[masked]", policy.apply("cpf", "12345678900"))
    }

    @Test
    fun `mascarar nao inventa valor onde havia nulo`() {
        assertNull(DataMaskingPolicy.NONE.apply("senha", null))
        assertEquals("[masked]", DataMaskingPolicy.NONE.apply("senha", "secreta"))
        assertEquals("Maria", DataMaskingPolicy.NONE.apply("nome", "Maria"))
    }

    /**
     * Um typo nao pode ser reportado como recusa de categoria: quem le "so SELECT e aceito" depois
     * de ter escrito um SELECT reescreve a consulta carregando o mesmo erro.
     */
    @Test
    fun `erro de sintaxe se distingue de recusa de categoria`() {
        val sintaxe = SqlStatementClassifier.classify("SELECT 1 FRON servidor") as SqlClassification.Denied
        val categoria = SqlStatementClassifier.classify("DELETE FROM servidor") as SqlClassification.Denied

        assertEquals(SqlStatementType.UNPARSEABLE, sintaxe.type)
        assertTrue(sintaxe.reason.contains("syntax problem"), sintaxe.reason)
        assertFalse(sintaxe.reason.contains("Only read statements"), sintaxe.reason)

        assertEquals(SqlStatementType.WRITE, categoria.type)
        assertTrue(categoria.reason.contains("Only read statements"), categoria.reason)
    }

}
