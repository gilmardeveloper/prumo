package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Uma coluna chega ao cliente por mais de um nome, e a ofuscação precisa reconhecê-la por todos.
 *
 * Decidir só pelo rótulo da consulta fazia `SELECT num_cpf AS codigo` devolver o documento em claro
 * — o mesmo apelido que já derrubara a máscara de segredo numa versão anterior. Cada caso abaixo é
 * uma forma de renomear ou transformar a coluna, e nenhuma pode desligar a proteção.
 */
@Tag("security")
class ObfuscationBypassTest {

    private fun classify(vararg names: String) =
        PersonalDataObfuscator.classify(names.toList(), "12345678901")

    @Test
    fun `apelido inocente nao desliga a ofuscacao quando a origem denuncia`() {
        assertEquals(PersonalDataKind.CPF, classify("codigo", "num_cpf"))
    }

    @Test
    fun `rotulo que anuncia dado pessoal decide mesmo sem coluna de origem`() {
        assertEquals(PersonalDataKind.CPF, classify("num_cpf", ""))
    }

    @Test
    fun `identificador vindo da expressao decide na coluna calculada`() {
        assertEquals(PersonalDataKind.CPF, classify("substr", "", "num_cpf"))
        assertEquals(PersonalDataKind.NAME, classify("upper", "", "txt_nome"))
    }

    @Test
    fun `nenhum dos nomes anunciando dado pessoal deixa o valor passar`() {
        assertEquals(PersonalDataKind.NONE, classify("total", "isn_pessoa"))
    }

    @Test
    fun `o nome mais especifico vence quando dois casam`() {
        assertEquals(PersonalDataKind.CPF, classify("num_cpf_responsavel", "num_cpf"))
    }

    @Test
    fun `a categoria sobrevive a lista fora de ordem`() {
        assertEquals(classify("num_cpf", "codigo"), classify("codigo", "num_cpf"))
    }

    /**
     * A máscara de segredo recua para os identificadores do statement inteiro quando a lista de
     * seleção não pode ser mapeada. A ofuscação nasceu sem esse recuo: com `WITH`, `UNION` ou
     * expansão por `*`, uma coluna calculada e renomeada ficava só com o rótulo escolhido.
     */
    @Test
    fun `o statement inteiro identifica a coluna quando a lista nao e mapeavel`() {
        val sql = "WITH x AS (SELECT num_cpf AS codigo FROM db_sgp.tb_pessoa) SELECT codigo FROM x"

        assertNull(
            SensitiveColumnScanner.identifiersByPosition(sql, 1),
            "a premissa do teste caiu: esta consulta passou a ser mapeável",
        )
        val doStatement = SensitiveColumnScanner.allIdentifiers(sql)
        assertEquals(
            PersonalDataKind.CPF,
            PersonalDataObfuscator.classify(listOf("codigo") + doStatement, "12345678901"),
        )
    }

    @Test
    fun `o recuo enxerga a coluna citada em qualquer ponto do statement`() {
        val sql = "SELECT * FROM db_sgp.tb_pessoa WHERE num_cpf IS NOT NULL"

        assertTrue(SensitiveColumnScanner.allIdentifiers(sql).any { it == "num_cpf" })
    }

    /**
     * Recortar um pedaço do documento e ofuscar o pedaço não protege: a janela cai sobre o recorte,
     * e iterar o deslocamento reconstrói o original. Valor derivado de expressão é escondido inteiro.
     */
    @Test
    fun `pedaco recortado de dado pessoal e escondido por inteiro`() {
        val kind = PersonalDataKind.EMAIL

        assertEquals("******", PersonalDataObfuscator.obfuscate(kind, "elle.l", derived = true))
        assertEquals("*****", PersonalDataObfuscator.obfuscate(PersonalDataKind.REGISTRY_NUMBER, "20910", derived = true))
    }

    @Test
    fun `valor sem o formato da categoria e escondido, nao liberado`() {
        assertEquals("******", PersonalDataObfuscator.obfuscate(PersonalDataKind.EMAIL, "elle.l"))
        assertEquals(
            "**************************",
            PersonalDataObfuscator.obfuscate(PersonalDataKind.CPF, "isabelle.lpontes@gmail.com"),
        )
    }

    @Test
    fun `a coluna real continua com a janela util`() {
        assertEquals("123***789**", PersonalDataObfuscator.obfuscate(PersonalDataKind.CPF, "12345678901"))
        assertEquals(
            "jo********@seplag.ce.gov.br",
            PersonalDataObfuscator.obfuscate(PersonalDataKind.EMAIL, "joao.silva@seplag.ce.gov.br"),
        )
    }

    @Test
    fun `o valor nunca sai igual ao original por nenhuma das vias`() {
        val original = "12345678901"
        listOf(
            listOf("codigo", "num_cpf"),
            listOf("num_cpf", ""),
            listOf("substr", "", "num_cpf"),
        ).forEach { nomes ->
            val kind = PersonalDataObfuscator.classify(nomes, original)
            assertNotEquals(
                original,
                PersonalDataObfuscator.obfuscate(kind, original),
                "o valor saiu intacto por $nomes",
            )
        }
    }
}
