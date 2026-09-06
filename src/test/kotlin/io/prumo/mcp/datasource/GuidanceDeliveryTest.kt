package io.prumo.mcp.datasource

import io.prumo.mcp.datasource.application.QueryColumn
import io.prumo.mcp.datasource.application.QueryOutcome
import io.prumo.mcp.datasource.security.ObfuscationGuidance
import io.prumo.mcp.datasource.security.PersonalDataKind
import io.prumo.mcp.datasource.security.SqlStatementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A orientação só fala quando há o que dizer.
 *
 * Repeti-la numa consulta que não tocou dado pessoal é ruído competindo com o dado, e a medição da
 * investigação mostrou que o texto pesa perto de um quarto de uma resposta pequena.
 */
@Tag("security")
class GuidanceDeliveryTest {

    private fun outcome(
        columns: List<QueryColumn>,
        obfuscated: Boolean = true,
    ) = QueryOutcome(
        statementType = SqlStatementType.SELECT,
        columns = columns,
        rows = emptyList(),
        rowCount = 0,
        truncated = false,
        durationMillis = 1,
        personalDataObfuscated = obfuscated,
    )

    private fun column(name: String, kind: PersonalDataKind = PersonalDataKind.NONE) = QueryColumn(
        name = name,
        type = "varchar",
        masked = false,
        identifiers = listOf(name),
        obfuscatedAs = kind,
    )

    @Test
    fun `consulta sem dado pessoal nao recebe orientacao`() {
        val resultado = outcome(listOf(column("total"), column("isn_pessoa")))

        assertNull(ObfuscationGuidance.forResult(resultado))
    }

    @Test
    fun `consulta com coluna escondida recebe a orientacao da protecao`() {
        val resultado = outcome(listOf(column("num_cpf", PersonalDataKind.CPF), column("total")))

        assertEquals(ObfuscationGuidance.forObfuscatedResult(), ObfuscationGuidance.forResult(resultado))
    }

    /**
     * Com o interruptor desligado toda coluna vem com categoria `NONE`, indistinguível de uma
     * consulta que não tocou dado pessoal. Sem o aviso, a IA não sabe que recebeu dado real.
     */
    @Test
    fun `protecao desligada sobre coluna pessoal recebe o aviso correspondente`() {
        val resultado = outcome(listOf(column("num_cpf")), obfuscated = false)

        assertEquals(ObfuscationGuidance.forUnprotectedResult(), ObfuscationGuidance.forResult(resultado))
    }

    @Test
    fun `protecao desligada sem coluna pessoal continua em silencio`() {
        val resultado = outcome(listOf(column("total"), column("isn_orgao")), obfuscated = false)

        assertNull(ObfuscationGuidance.forResult(resultado))
    }

    @Test
    fun `a orientacao do banco distingue protegido de desprotegido`() {
        val protegido = ObfuscationGuidance.forDatasource(true)
        val desprotegido = ObfuscationGuidance.forDatasource(false)

        assertTrue(protegido.contains("partially hidden"), protegido)
        assertTrue(desprotegido.contains("complete and unmasked"), desprotegido)
    }
}
