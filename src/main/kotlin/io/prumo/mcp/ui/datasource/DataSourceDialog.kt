package io.prumo.mcp.ui.datasource

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
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
 */
class DataSourceDialog(
    private val project: Project,
    private val workspaceId: String,
    private val existing: DataSourceProfile? = null,
    private val credentials: CredentialProvider,
    private val probe: PostgresConnectionProbe = PostgresConnectionProbe(),
) : DialogWrapper(project) {

    private var name: String = existing?.name.orEmpty()
    private var host: String = existing?.host ?: "localhost"
    private var port: Int = existing?.port ?: DataSourceProfile.DEFAULT_PORT
    private var database: String = existing?.database.orEmpty()
    private var user: String = existing?.user.orEmpty()
    private var accessMode: AccessMode = existing?.accessMode ?: AccessMode.READ_ONLY
    private var sslMode: SslMode = existing?.sslMode ?: SslMode.PREFER
    private var defaultSchema: String = existing?.defaultSchema.orEmpty()

    private val passwordField = JBPasswordField()
    private val testResult = JBLabel(" ")

    init {
        title = PrumoBundle.message(if (existing == null) "datasource.dialog.title.add" else "datasource.dialog.title.edit")
        setOKButtonText(PrumoBundle.message("workspace.dialog.save"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(PrumoBundle.message("datasource.field.name")) { textField().bindText(::name).columns(28).focused() }
        row(PrumoBundle.message("datasource.field.host")) { textField().bindText(::host).columns(28) }
        row(PrumoBundle.message("datasource.field.port")) { intTextField(1..65535).bindIntText(::port).columns(6) }
        row(PrumoBundle.message("datasource.field.database")) { textField().bindText(::database).columns(28) }
        row(PrumoBundle.message("datasource.field.user")) { textField().bindText(::user).columns(28) }
        row(PrumoBundle.message("datasource.field.password")) { cell(passwordField).columns(28) }
        row(PrumoBundle.message("datasource.field.access")) {
            comboBox(AccessMode.entries).bindItem({ accessMode }, { accessMode = it ?: AccessMode.READ_ONLY })
        }
        row(PrumoBundle.message("datasource.field.sslMode")) {
            comboBox(SslMode.entries).bindItem({ sslMode }, { sslMode = it ?: SslMode.PREFER })
        }
        row(PrumoBundle.message("datasource.field.defaultSchema")) { textField().bindText(::defaultSchema).columns(20) }

        row {
            button(PrumoBundle.message("datasource.test")) { testConnection() }
            cell(testResult)
        }
        row {
            comment(PrumoBundle.message("datasource.hint"))
        }
    }.apply { border = JBUI.Borders.empty(8) }

    override fun doValidate(): ValidationInfo? = when {
        name.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.name"))
        host.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.host"))
        database.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.database"))
        user.isBlank() -> ValidationInfo(PrumoBundle.message("datasource.validation.user"))
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
}
