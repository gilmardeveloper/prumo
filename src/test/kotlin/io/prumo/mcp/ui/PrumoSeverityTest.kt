package io.prumo.mcp.ui

import io.prumo.mcp.pack.domain.RiskLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A aparência do nível de risco.
 *
 * O nível decide se um pack pode ser instalado. Nível sem ícone cairia em exceção na montagem da
 * tela; nível resolvido para o valor errado o faria parecer mais seguro do que é.
 */
class PrumoSeverityTest {

    @Test
    fun `todo nivel tem recurso de icone nas duas variantes`() {
        val faltando = RiskLevel.entries.flatMap { level ->
            val nome = ICONS.getValue(level)
            listOf("$nome.svg", "${nome}_dark.svg")
        }.filterNot { arquivo -> Path.of("src/main/resources/icons", arquivo).exists() }

        assertTrue(faltando.isEmpty(), "faltam ícones: $faltando")
    }

    @Test
    fun `todo nivel tem cor`() {
        RiskLevel.entries.forEach { level ->
            assertNotNull(PrumoSeverity.colorFor(level), "sem cor para $level")
        }
    }

    @Test
    fun `cada nivel tem cor propria`() {
        val cores = RiskLevel.entries.map { PrumoSeverity.colorFor(it).rgb }
        assertEquals(cores.size, cores.distinct().size, "dois níveis compartilham a mesma cor")
    }

    @Test
    fun `o nome gravado volta ao nivel correspondente`() {
        RiskLevel.entries.forEach { level ->
            assertEquals(level, PrumoSeverity.levelOf(level.name))
            assertEquals(level, PrumoSeverity.levelOf(level.name.lowercase()))
            assertEquals(level, PrumoSeverity.levelOf("  ${level.name}  "))
        }
    }

    @Test
    fun `nivel desconhecido nao pode parecer seguro`() {
        listOf("", "   ", "LOW", "critical", "SAFE_ISH", "0").forEach { entrada ->
            assertEquals(RiskLevel.BLOCKED, PrumoSeverity.levelOf(entrada), "entrada: '$entrada'")
        }
    }

    private companion object {
        val ICONS = mapOf(
            RiskLevel.SAFE to "riskSafe",
            RiskLevel.SENSITIVE to "riskSensitive",
            RiskLevel.DESTRUCTIVE to "riskDestructive",
            RiskLevel.BLOCKED to "riskBlocked",
        )
    }
}
