package io.prumo.mcp.pack

import io.prumo.mcp.pack.domain.KnowledgeItem
import io.prumo.mcp.pack.domain.LocalizedText
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.PackTool
import io.prumo.mcp.pack.domain.PackToolKind
import io.prumo.mcp.pack.domain.RiskClassifier
import io.prumo.mcp.pack.domain.RiskLevel
import io.prumo.mcp.policy.Capability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A especificação de autoria afirma garantias, e um pack é código de terceiro.
 *
 * Cada teste aqui corresponde a uma frase que a especificação entrega ao autor. Frase sem imposição
 * é promessa vazia, e num pack o custo dela é alto: o arquivo viaja para outra máquina.
 */
@Tag("security")
class PackPromiseTest {

    private fun text(value: String) = LocalizedText(value)

    private fun knowledge(file: String) = KnowledgeItem(id = "k", title = text("k"), file = file)

    private fun manifest(tools: List<PackTool> = emptyList()) = PackManifest(
        id = "pack",
        title = text("p"),
        description = text("d"),
        version = "1.0.0",
        capabilities = setOf(Capability.DATASOURCE_QUERY),
        tools = tools,
        createdAt = "2026-09-06T00:00:00Z",
        updatedAt = "2026-09-06T00:00:00Z",
    )

    private fun query(datasourceRef: String? = "dev", sql: String = "SELECT 1") = PackTool(
        id = "q",
        title = text("q"),
        description = text("d"),
        kind = PackToolKind.QUERY,
        datasourceRef = datasourceRef,
        sql = sql,
    )

    @Test
    fun `o caminho relativo dentro do pack e aceito`() {
        knowledge("knowledge/regras.md")
    }

    /** "Path inside the pack. Absolute paths are refused." */
    @Test
    fun `caminho absoluto no conhecimento e recusado`() {
        listOf("/etc/passwd", "C:/Users/dev/.ssh/id_rsa", "\\\\servidor\\share\\x.md").forEach { caminho ->
            assertThrows(IllegalArgumentException::class.java, { knowledge(caminho) }, "aceitou '$caminho'")
        }
    }

    @Test
    fun `travessia no conhecimento e recusada`() {
        listOf("../fora.md", "docs/../../fora.md", "docs\\..\\..\\fora.md").forEach { caminho ->
            assertThrows(IllegalArgumentException::class.java, { knowledge(caminho) }, "aceitou '$caminho'")
        }
    }

    /** "Logical id of the data source … Never a connection string." */
    @Test
    fun `referencia de banco por identificador logico e aceita`() {
        val risco = RiskClassifier.assess(manifest(listOf(query(datasourceRef = "dev"))))

        assertEquals(RiskLevel.SAFE, risco.level, risco.findings.toString())
    }

    @Test
    fun `connection string como referencia de banco e recusada`() {
        listOf(
            "jdbc:postgresql://10.0.0.5:5432/folha",
            "postgresql://host/base",
            "mysql://host/base",
        ).forEach { referencia ->
            val risco = RiskClassifier.assess(manifest(listOf(query(datasourceRef = referencia))))

            assertEquals(RiskLevel.BLOCKED, risco.level, "aceitou '$referencia'")
        }
    }

    /** "Never put a password, token or connection string inside a pack." */
    @Test
    fun `segredo dentro do pack e recusado`() {
        val comSenha = query(sql = "SELECT 1 -- password=Tr0cad0!2024")
        val risco = RiskClassifier.assess(manifest(listOf(comSenha)))

        assertEquals(RiskLevel.BLOCKED, risco.level, risco.findings.toString())
    }

    /** A evidência de um achado vai para a tela do desenvolvedor: não pode carregar o segredo. */
    @Test
    fun `a evidencia do achado nao repete o segredo`() {
        val risco = RiskClassifier.assess(
            manifest(listOf(query(datasourceRef = "jdbc:postgresql://host/base?password=Tr0cad0!2024"))),
        )

        risco.findings.forEach { achado ->
            assertFalse(achado.evidence.contains("Tr0cad0"), "a evidência vazou o segredo: ${achado.evidence}")
            assertFalse(achado.explanation.contains("Tr0cad0"), "a explicação vazou o segredo")
        }
    }

    @Test
    fun `consulta legitima sem segredo continua segura`() {
        val risco = RiskClassifier.assess(
            manifest(listOf(query(sql = "SELECT nome FROM servidor WHERE ativo"))),
        )

        assertTrue(risco.findings.isEmpty(), risco.findings.toString())
    }
}
