package io.prumo.mcp.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ui.PrumoToolWindowFactory
import java.io.IOException
import javax.swing.JComponent

/**
 * *Settings · Tools · Prumo MCP* — onde o idioma da interface é escolhido.
 *
 * O nome exibido é resolvido em código, e não pelo `<resource-bundle>` do `plugin.xml`: aquele
 * segue o idioma da IDE, e uma entrada de menu numa língua abrindo uma tela em outra é exatamente
 * a tela mista que a preferência existe para evitar.
 */
class PrumoLanguageConfigurable : Configurable {

    private var selected: PrumoLanguage = PrumoLanguageSetting.shared.current()
    private var form: DialogPanel? = null

    override fun getDisplayName(): String = PrumoBundle.message("toolwindow.title")

    override fun createComponent(): JComponent = panel {
        row(PrumoBundle.message("settings.language.label")) {
            comboBox(
                PrumoLanguage.entries,
                SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) },
            ).bindItem({ selected }, { selected = it ?: PrumoLanguage.SYSTEM })
        }
        row {
            comment(PrumoBundle.message("settings.language.hint"))
        }
    }.also { form = it }

    override fun isModified(): Boolean = form?.isModified() == true

    override fun apply() {
        form?.apply()
        try {
            PrumoLanguageSetting.shared.update(selected)
        } catch (cause: IOException) {
            // Sem isto a IDE mostraria um relatório de erro interno em vez de dizer o que falhou.
            throw ConfigurationException(
                PrumoBundle.message("settings.language.error.notSaved", cause.message.orEmpty()),
            )
        }
        PrumoToolWindowFactory.refreshOpenProjects()
    }

    override fun reset() {
        selected = PrumoLanguageSetting.shared.current()
        form?.reset()
    }

    override fun disposeUIResources() {
        form = null
    }
}
