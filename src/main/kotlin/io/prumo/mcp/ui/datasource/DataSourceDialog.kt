package io.prumo.mcp.ui.datasource

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.datasource.ConnectionTestOutcome
import io.prumo.mcp.datasource.DataSourceAudit
import io.prumo.mcp.datasource.PostgresConnectionProbe
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import io.prumo.mcp.datasource.messageKey
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.ui.ConfigureWorkspaceAction
import io.prumo.mcp.ui.labelKey
import io.prumo.mcp.workspace.domain.AccessMode
import java.util.Arrays
import javax.swing.JComponent

/**
 * Cadastro de um banco do workspace corrente.
 *
 * A senha digitada não vai para o objeto gravado em disco: segue para o cofre da IDE quando o
 * formulário é aceito.
 *
 * O teste de conexão informa o desfecho, nunca a mensagem do driver.
 *
 * Os campos são lidos direto dos componentes. O `DialogPanel` só copia valor ligado para a
 * propriedade quando `apply()` roda, e a plataforma o chama depois de validar — validação e teste de
 * conexão enxergariam o formulário como ele nasceu.
 */
class DataSourceDialog(
    private val project: Project,
    private val workspaceId: String,
    private val existing: DataSourceProfile? = null,
    private val credentials: CredentialProvider,
    private val probe: PostgresConnectionProbe = PostgresConnectionProbe(),
) : DialogWrapper(project) {

    private val nameField = JBTextField(existing?.name.orEmpty())
    private val hostField = JBTextField(existing?.host ?: "localhost")
    private val portField = JBTextField((existing?.port ?: DataSourceProfile.DEFAULT_PORT).toString())
    private val databaseField = JBTextField(existing?.database.orEmpty())
    private val userField = JBTextField(existing?.user.orEmpty())
    private val passwordField = JBPasswordField()
    private val defaultSchemaField = JBTextField(existing?.defaultSchema.orEmpty())

    private val descriptionArea = JBTextArea(existing?.description.orEmpty(), DESCRIPTION_ROWS, DESCRIPTION_COLUMNS)
        .apply { lineWrap = true; wrapStyleWord = true }

    private val accessModeBox = ComboBox(AccessMode.entries.toTypedArray()).apply {
        selectedItem = existing?.accessMode ?: AccessMode.READ_ONLY
        renderer = SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) }
    }

    private val sslModeBox = ComboBox(SslMode.entries.toTypedArray())
        .apply { selectedItem = existing?.sslMode ?: SslMode.PREFER }

    private val testResult = JBLabel(" ")

    private val name: String get() = nameField.text.trim()
    private val host: String get() = hostField.text.trim()
    private val database: String get() = databaseField.text.trim()
    private val user: String get() = userField.text.trim()
    private val defaultSchema: String get() = defaultSchemaField.text.trim()
    private val description: String get() = descriptionArea.text.trim()
    private val accessMode: AccessMode get() = accessModeBox.selectedItem as? AccessMode ?: AccessMode.READ_ONLY
    private val sslMode: SslMode get() = sslModeBox.selectedItem as? SslMode ?: SslMode.PREFER

    /** Porta ilegível vira zero, que a validação recusa junto com qualquer valor fora da faixa. */
    private val port: Int get() = portField.text.trim().toIntOrNull() ?: 0

    init {
        title = PrumoBundle.message(if (existing == null) "datasource.dialog.title.add" else "datasource.dialog.title.edit")
        setOKButtonText(PrumoBundle.message("workspace.dialog.save"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(PrumoBundle.message("datasource.field.name")) { cell(nameField).columns(28).focused() }
        row(PrumoBundle.message("datasource.field.host")) { cell(hostField).columns(28) }
        row(PrumoBundle.message("datasource.field.port")) { cell(portField).columns(6) }
        row(PrumoBundle.message("datasource.field.database")) { cell(databaseField).columns(28) }
        row(PrumoBundle.message("datasource.field.user")) { cell(userField).columns(28) }
        row(PrumoBundle.message("datasource.field.password")) { cell(passwordField).columns(28) }
        row(PrumoBundle.message("datasource.field.access")) { cell(accessModeBox) }

        row(PrumoBundle.message("datasource.field.sslMode")) { cell(sslModeBox) }
        row(PrumoBundle.message("datasource.field.defaultSchema")) { cell(defaultSchemaField).columns(20) }
        row(PrumoBundle.message("datasource.field.description")) { cell(JBScrollPane(descriptionArea)) }
        row { comment(PrumoBundle.message("datasource.descriptionHint"), maxLineLength = 62) }

        row {
            button(PrumoBundle.message("datasource.test")) { testConnection() }
            cell(testResult)
        }
        row {
            comment(PrumoBundle.message("datasource.hint"))
        }
    }.apply { border = JBUI.Borders.empty(8) }

    override fun doValidate(): ValidationInfo? = when {
        name.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.name"), nameField)
        host.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.host"), hostField)
        port !in PORT_RANGE -> ValidationInfo(PrumoBundle.message("datasource.validation.port"), portField)
        database.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.database"), databaseField)
        user.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.user"), userField)
        description.length > DataSourceProfile.MAX_DESCRIPTION_LENGTH -> ValidationInfo(
            PrumoBundle.message(
                "datasource.validation.description",
                DataSourceProfile.MAX_DESCRIPTION_LENGTH,
                description.length,
            ),
            descriptionArea,
        )
        else -> null
    }

    /** Conecta fora da thread da interface, para a IDE não congelar enquanto o banco responde. */
    private fun testConnection() {
        val profile = buildProfile() ?: run {
            testResult.text = PrumoBundle.message("datasource.test.incomplete")
            return
        }
        val password = passwordOrStored(profile)
        val startedAt = System.nanoTime()
        val outcome = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<ConnectionTestOutcome, Exception>(
                { probe.test(profile, password) },
                PrumoBundle.message("datasource.test.progress"),
                true,
                project,
            )
        } finally {
            password?.let { Arrays.fill(it, '\u0000') }
        }

        testResult.text = PrumoBundle.message(outcome.messageKey)
        DataSourceAudit.recordConnectionTest(
            log = PrumoWorkspaceService.getInstance().audit,
            workspaceId = workspaceId,
            profile = profile,
            outcome = outcome,
            durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /** A senha digitada tem precedência; sem ela, testa com a que já está no cofre. */
    private fun passwordOrStored(profile: DataSourceProfile): CharArray? =
        passwordField.password.takeIf { it.isNotEmpty() }
            ?: credentials.password(CredentialKey(workspaceId, profile.id), profile.user)

    private fun buildProfile(): DataSourceProfile? {
        if (doValidate() != null) {
            return null
        }
        return DataSourceProfile(
            id = existing?.id ?: ConfigureWorkspaceAction.slug(name),
            name = name,
            host = host,
            port = port,
            database = database,
            user = user,
            accessMode = accessMode,
            sslMode = sslMode,
            defaultSchema = defaultSchema.ifBlank { null },
            description = description.ifBlank { null },
        )
    }

    /**
     * Perfil aceito, já com a senha guardada no cofre.
     *
     * A gravação da senha acontece aqui, e não no chamador, para que não exista caminho em que o
     * perfil é salvo e o segredo fica para trás em uma variável.
     */
    fun toProfile(): DataSourceProfile {
        val profile = requireNotNull(buildProfile()) { "The data source form is incomplete." }
        val typed = passwordField.password
        if (typed.isNotEmpty()) {
            try {
                credentials.store(CredentialKey(workspaceId, profile.id), profile.user, typed)
            } finally {
                Arrays.fill(typed, '\u0000')
            }
        }
        return profile
    }

    private companion object {
        const val DESCRIPTION_ROWS = 4
        const val DESCRIPTION_COLUMNS = 40
        val PORT_RANGE = 1..65535
    }
}
