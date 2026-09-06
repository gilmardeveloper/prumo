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
 * O nome exibido vem do `displayName` do `plugin.xml`, que a plataforma exige para não carregar esta
 * classe ao abrir Settings. [getDisplayName] devolve o mesmo texto, para quando ela consulta a
 * instância.
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
