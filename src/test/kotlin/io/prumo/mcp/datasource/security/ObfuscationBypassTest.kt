package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
