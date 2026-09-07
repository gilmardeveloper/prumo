package io.prumo.mcp.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import io.prumo.mcp.pack.domain.RiskLevel
import javax.swing.Icon

/**
 * Aparência de cada nível de risco de pack: ícone e cor.
 *
 * O nível decide se um pack pode ser instalado e sob quais condições. Em texto puro, `SAFE` e
 * `DESTRUCTIVE` têm o mesmo peso na tela.
 */
object PrumoSeverity {

    /** Ícone do nível, resolvido pela variante clara ou escura conforme o tema da IDE. */
    fun iconFor(level: RiskLevel): Icon = when (level) {
        RiskLevel.SAFE -> icon("riskSafe")
        RiskLevel.SENSITIVE -> icon("riskSensitive")
        RiskLevel.DESTRUCTIVE -> icon("riskDestructive")
        RiskLevel.BLOCKED -> icon("riskBlocked")
    }

    /** Cor do nível, com par claro/escuro. */
    fun colorFor(level: RiskLevel): JBColor = when (level) {
        RiskLevel.SAFE -> JBColor(0x59A869, 0x499C54)
        RiskLevel.SENSITIVE -> JBColor(0xEDA200, 0xF0A732)
        RiskLevel.DESTRUCTIVE -> JBColor(0xE05555, 0xFF6B68)
        RiskLevel.BLOCKED -> JBColor(0xDB5860, 0xC75450)
    }

    /**
     * Converte o nível gravado como texto.
     *
     * Nível que este produto não conhece é tratado como `BLOCKED`: um pack cujo risco não se
     * consegue ler não pode parecer seguro na tela.
     */
    fun levelOf(name: String): RiskLevel =
        RiskLevel.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: RiskLevel.BLOCKED

    private fun icon(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", PrumoSeverity::class.java)
}
