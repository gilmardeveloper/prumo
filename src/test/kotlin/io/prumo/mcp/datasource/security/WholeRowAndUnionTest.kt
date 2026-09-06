package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Duas formas de a expressão deixar de citar a coluna pelo nome, e com isso escapar da proteção.
 *
 * `row_to_json(t)` empacota o registro inteiro num valor só: nenhum nome de coluna sobra na
 * expressão, e o conteúdo é a pessoa completa. `UNION` combina braços cuja lista de seleção o
 * primeiro não descreve: `SELECT txt_sexo … UNION ALL SELECT num_cpf …` entrega o CPF sob a
 * classificação da primeira coluna.
 */
@Tag("security")
class WholeRowAndUnionTest {

    @Test
    fun `serializacao de linha inteira e reconhecida`() {
        listOf(
            "row_to_json(t)",
            "to_jsonb(t)",
            "to_json(p)",
            "jsonb_agg(t)",
            "json_agg(t)",
            "t::text",
            "t :: jsonb",
            "json_build_object('a', t)",
        ).forEach { item ->
            assertTrue(SensitiveColumnScanner.serializesWholeRow(item), "não reconheceu '$item'")
        }
    }

    @Test
    fun `expressao comum nao e confundida com serializacao de linha`() {
        listOf("count(*)", "upper(txt_nome)", "num_cpf", "sum(valor)", "length(txt_email)").forEach { item ->
            assertFalse(SensitiveColumnScanner.serializesWholeRow(item), "confundiu '$item'")
        }
    }

    @Test
    fun `operador de conjunto e reconhecido em qualquer das tres formas`() {
        listOf(
            "SELECT a FROM t UNION ALL SELECT b FROM u",
            "SELECT a FROM t union SELECT b FROM u",
            "SELECT a FROM t EXCEPT SELECT b FROM u",
            "SELECT a FROM t INTERSECT SELECT b FROM u",
        ).forEach { sql ->
            assertTrue(SensitiveColumnScanner.combinesResultSets(sql), "não reconheceu: $sql")
        }
    }

    @Test
    fun `consulta simples nao e tratada como combinacao`() {
        assertFalse(SensitiveColumnScanner.combinesResultSets("SELECT num_cpf FROM db_sgp.tb_pessoa"))
    }

    /**
     * Com operador de conjunto, o mapeamento por posição precisa ser abandonado: a posição 1 do
     * primeiro braço e a do segundo podem trazer colunas diferentes.
     */
    @Test
    fun `a lista de selecao nao e mapeada quando ha operador de conjunto`() {
        val sql = "SELECT txt_sexo AS c FROM db_sgp.tb_pessoa UNION ALL SELECT num_cpf FROM db_sgp.tb_pessoa"

        assertTrue(SensitiveColumnScanner.combinesResultSets(sql))
        assertTrue(
            SensitiveColumnScanner.allIdentifiers(sql).any { it == "num_cpf" },
            "o recuo precisa enxergar a coluna do segundo braço",
        )
    }

    @Test
    fun `o recuo classifica a coluna pelo statement inteiro na combinacao`() {
        val sql = "SELECT txt_sexo AS c FROM db_sgp.tb_pessoa UNION ALL SELECT num_cpf FROM db_sgp.tb_pessoa"
        val identificadores = listOf("c") + SensitiveColumnScanner.allIdentifiers(sql)

        assertTrue(
            PersonalDataObfuscator.classify(identificadores, "12345678901") != PersonalDataKind.NONE,
            "a coluna combinada saiu sem classificação",
        )
    }

    @Test
    fun `a lista continua mapeavel sem operador de conjunto`() {
        val sql = "SELECT count(*) AS total, num_cpf FROM db_sgp.tb_pessoa GROUP BY num_cpf"

        assertFalse(SensitiveColumnScanner.combinesResultSets(sql))
        assertTrue(SensitiveColumnScanner.selectItemsOrNull(sql, 2)?.size == 2)
    }
}
