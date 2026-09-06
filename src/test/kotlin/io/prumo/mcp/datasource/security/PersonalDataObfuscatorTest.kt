package io.prumo.mcp.datasource.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A ofuscação precisa esconder o documento e continuar deixando a IA trabalhar.
 *
 * Os dois lados são testados: o que sai da tela e o que permanece útil.
 */
@Tag("security")
class PersonalDataObfuscatorTest {

    private fun obfuscate(column: String, value: String?): String? =
        PersonalDataObfuscator.obfuscate(PersonalDataObfuscator.classify(column, value), value)

    @Test
    fun `o CPF mostra tres digitos do inicio e tres do fim da base`() {
        assertEquals("123.***.789-**", obfuscate("num_cpf", "123.456.789-01"))
        assertEquals("123***789**", obfuscate("num_cpf", "12345678901"))
    }

    @Test
    fun `o digito verificador do CPF nunca aparece`() {
        val original = "12345678901"

        val ofuscado = obfuscate("num_cpf", original)!!

        assertEquals('*', ofuscado[9], "o primeiro verificador vazou: $ofuscado")
        assertEquals('*', ofuscado[10], "o segundo verificador vazou: $ofuscado")
    }

    @Test
    fun `CPF que diferem na janela visivel continuam distinguiveis`() {
        assertNotEquals(obfuscate("num_cpf", "12345678901"), obfuscate("num_cpf", "12345678701"))
        assertNotEquals(obfuscate("num_cpf", "12345678901"), obfuscate("num_cpf", "92345678901"))
    }

    /**
     * Esconder dígitos implica colidir: dois valores que só diferem na parte escondida saem iguais.
     *
     * É o preço da ofuscação, e o motivo pelo qual valor ofuscado não serve como chave. A junção e o
     * agrupamento continuam corretos porque acontecem no banco, sobre o valor real.
     */
    @Test
    fun `valores que diferem apenas na parte escondida colidem`() {
        assertEquals(obfuscate("num_cpf", "12345678901"), obfuscate("num_cpf", "12345178901"))
    }

    @Test
    fun `o mesmo valor produz sempre a mesma saida`() {
        assertEquals(obfuscate("num_cpf", "12345678901"), obfuscate("num_cpf", "12345678901"))
    }

    @Test
    fun `o telefone preserva o DDD e os quatro ultimos`() {
        assertEquals("(85) *****-4321", obfuscate("num_telefone", "(85) 98765-4321"))
        assertEquals("85*****4321", obfuscate("celular", "85987654321"))
    }

    @Test
    fun `o nome preserva o primeiro e reduz o resto a inicial`() {
        assertEquals("Maria S. S.", obfuscate("txt_nome", "Maria Silva Santos"))
        assertEquals("Maria", obfuscate("nome_mae", "Maria"))
    }

    @Test
    fun `o email preserva o dominio, que descreve a organizacao`() {
        val ofuscado = obfuscate("txt_email", "joao.silva@seplag.ce.gov.br")!!

        assertTrue(ofuscado.endsWith("@seplag.ce.gov.br"), ofuscado)
        assertTrue(ofuscado.startsWith("jo"), ofuscado)
        assertFalse(ofuscado.contains("silva"), ofuscado)
    }

    @Test
    fun `a data de nascimento preserva o ano`() {
        assertEquals("1975-**-**", obfuscate("data_nascimento", "1975-03-14"))
        assertEquals("**/**/1975", obfuscate("dat_nascimento", "14/03/1975"))
    }

    @Test
    fun `a conta bancaria mostra os quatro ultimos`() {
        assertEquals("*****4321", obfuscate("num_conta_corrente", "123454321"))
    }

    @Test
    fun `o RG usa janela menor por nao ter verificador padronizado`() {
        assertEquals("20***56", obfuscate("txt_numero_rg", "2012356"))
    }

    @Test
    fun `PIS e cartao do SUS escondem o verificador`() {
        val pis = obfuscate("txt_pis_pasep", "12345678901")!!

        assertEquals('*', pis.last(), "o verificador do PIS vazou: $pis")
        assertTrue(pis.startsWith("123"), pis)
    }

    @Test
    fun `coluna sem dado pessoal sai intacta`() {
        assertEquals("2262", obfuscate("total", "2262"))
        assertEquals("SEDUC", obfuscate("sigla_orgao", "SEDUC"))
        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify("isn_pessoa", "42"))
    }

    @Test
    fun `nulo e vazio atravessam sem alteracao`() {
        assertNull(obfuscate("num_cpf", null))
        assertEquals("", obfuscate("num_cpf", ""))
    }

    @Test
    fun `formato que nao confirma o nome cai na janela mais restritiva`() {
        val kind = PersonalDataObfuscator.classify("num_cpf", "123")

        assertEquals(PersonalDataKind.REGISTRY_NUMBER, kind, "valor sem cara de CPF não pode ser liberado")
    }

    @Test
    fun `valor curto demais para a janela e escondido por inteiro`() {
        assertEquals(PersonalDataObfuscator.HIDDEN_VALUE, obfuscate("num_cpf", "1234"))
    }

    @Test
    fun `a classificacao ignora a caixa do nome da coluna`() {
        assertEquals(PersonalDataKind.CPF, PersonalDataObfuscator.classify("NUM_CPF", "12345678901"))
    }

    /**
     * Ofuscar demais custa o trabalho legítimo: um código de órgão escondido inutiliza a junção que
     * o usa. Os casos abaixo apareceram numa varredura de tabela real.
     */
    @Test
    fun `fragmento curto nao casa dentro de outra palavra`() {
        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify("isn_orgao_origem", "3"))
        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify("txt_cargo", "Analista"))
        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify("num_energia", "120"))
    }

    /**
     * Campo que acompanha um documento continua tratado como parte dele.
     *
     * `isn_orgao_rg` traz o segmento `rg` de propósito — é o órgão emissor. Distinguir o número do
     * documento dos campos que o descrevem exigiria semântica que o nome não carrega, e a escolha é
     * proteger a mais.
     */
    @Test
    fun `campo que acompanha o documento segue protegido`() {
        assertEquals(PersonalDataKind.REGISTRY_NUMBER, PersonalDataObfuscator.classify("isn_orgao_rg", "12"))
    }

    @Test
    fun `fragmento curto continua valendo como segmento inteiro`() {
        assertEquals(PersonalDataKind.REGISTRY_NUMBER, PersonalDataObfuscator.classify("txt_rg", "2012356"))
        assertEquals(PersonalDataKind.NATIONAL_ID, PersonalDataObfuscator.classify("num_pis", "12345678901"))
    }

    @Test
    fun `coluna booleana nao e tratada como dado pessoal`() {
        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify("flg_utilizar_nome_social", "S"))
        assertEquals(PersonalDataKind.NONE, PersonalDataObfuscator.classify("ind_email_valido", "1"))
    }

    @Test
    fun `o dado pessoal de verdade continua reconhecido`() {
        assertEquals(PersonalDataKind.CPF, PersonalDataObfuscator.classify("num_cpf", "12345678901"))
        assertEquals(PersonalDataKind.NAME, PersonalDataObfuscator.classify("dsc_nome_social", "Maria Silva"))
        assertEquals(PersonalDataKind.EMAIL, PersonalDataObfuscator.classify("txt_email", "a@b.com"))
    }

    @Test
    fun `nenhuma saida preserva o valor original de um documento`() {
        val documentos = mapOf(
            "num_cpf" to "12345678901",
            "txt_pis_pasep" to "98765432100",
            "num_telefone" to "85987654321",
            "txt_email" to "joao.silva@seplag.ce.gov.br",
        )

        documentos.forEach { (coluna, valor) ->
            assertNotEquals(valor, obfuscate(coluna, valor), "o valor de '$coluna' saiu intacto")
        }
    }
}
