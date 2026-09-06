package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Um apelido de subconsulta esconde a coluna que ele renomeia.
 *
 * `SELECT upper(t.a) FROM (SELECT txt_email AS a FROM …) t` deixa o driver sem coluna de origem e o
 * rótulo sem nada que denuncie o dado: sobra o identificador `a`. Foi assim que e-mail, CPF e RG
 * saíram completos numa consulta de uma linha.
 */
@Tag("security")
class AliasResolutionTest {

    @Test
    fun `o apelido interno e resolvido de volta a coluna que renomeia`() {
        val sql = "SELECT upper(t.a) AS y FROM (SELECT txt_email AS a FROM db_sgp.tb_pessoa) t"

        // Só o apelido que renomeia uma coluna entra: `y` apelida uma expressão, não uma coluna.
        assertEquals(mapOf("a" to "txt_email"), SensitiveColumnScanner.aliasOrigins(sql))
    }

    @Test
    fun `a coluna qualificada por tabela tambem e resolvida`() {
        val sql = "SELECT p.num_cpf AS doc FROM db_sgp.tb_pessoa p"

        assertEquals("num_cpf", SensitiveColumnScanner.aliasOrigins(sql)["doc"])
    }

    @Test
    fun `expressao sobre apelido interno e classificada pela coluna de origem`() {
        val sql = "SELECT upper(t.a) AS y FROM (SELECT txt_email AS a FROM db_sgp.tb_pessoa) t"
        val origens = SensitiveColumnScanner.aliasOrigins(sql)
        val identificadores = listOf("y", "upper", "t", "a").flatMap {
            listOf(it) + listOfNotNull(origens[it])
        }

        assertEquals(PersonalDataKind.EMAIL, PersonalDataObfuscator.classify(identificadores, "a@b.com"))
    }

    @Test
    fun `apelido que nao renomeia coluna pessoal nao classifica nada`() {
        val sql = "SELECT upper(t.a) AS y FROM (SELECT txt_cargo AS a FROM db_sgp.tb_pessoa) t"
        val origens = SensitiveColumnScanner.aliasOrigins(sql)
        val identificadores = listOf("y", "upper", "t", "a").flatMap {
            listOf(it) + listOfNotNull(origens[it])
        }

        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify(identificadores, "Analista"))
    }

    @Test
    fun `o nome renomeado por uma view e reconhecido`() {
        assertEquals(PersonalDataKind.NAME, PersonalDataObfuscator.classify("nome_servidor", "Maria Silva"))
        assertEquals(PersonalDataKind.NAME, PersonalDataObfuscator.classify("nome_beneficiario", "Maria Silva"))
    }

    @Test
    fun `dado sensivel da LGPD e escondido por inteiro`() {
        listOf("num_raca_cor", "txt_deficiencia_fisica", "cod_cid", "txt_religiao").forEach { coluna ->
            val kind = PersonalDataObfuscator.classify(coluna, "6")
            assertNotEquals(PersonalDataKind.NONE, kind, "'$coluna' não foi reconhecido como sensível")
            assertEquals(PersonalDataObfuscator.HIDDEN_VALUE, PersonalDataObfuscator.obfuscate(kind, "6"))
        }
    }

    @Test
    fun `documento que faltava na cobertura passa a ser reconhecido`() {
        listOf(
            "num_id_funcional", "txt_carteira_profissional", "txt_documento_militar",
            "num_registro_nacional_estrangeiro_rne",
        ).forEach { coluna ->
            assertNotEquals(
                PersonalDataKind.NONE,
                PersonalDataObfuscator.classify(coluna, "14038776550"),
                "'$coluna' seguia sem classificação",
            )
        }
    }

    @Test
    fun `texto livre sobre a pessoa e escondido por inteiro`() {
        val kind = PersonalDataObfuscator.classify("txt_observacao_recadastramento", "mora com a mãe")

        assertEquals(PersonalDataKind.FREE_TEXT, kind)
        assertEquals(PersonalDataObfuscator.HIDDEN_VALUE, PersonalDataObfuscator.obfuscate(kind, "mora com a mãe"))
    }
}
