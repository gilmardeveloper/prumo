package io.prumo.mcp.workspace.application

import com.intellij.openapi.diagnostic.Logger
import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore

/**
 * Apaga um workspace por inteiro: os arquivos e as senhas que os bancos dele guardaram no cofre.
 *
 * A senha não vive no arquivo do workspace, então apagar o diretório não a alcança. Sem esta
 * remoção, um banco criado depois com o mesmo identificador, no workspace de mesmo nome, receberia
 * a senha anterior sem que ninguém a digitasse.
 */
class WorkspaceRemoval(
    private val store: WorkspaceStore,
    private val credentials: CredentialProvider,
) {

    /**
     * Remove as credenciais dos bancos do workspace e depois o diretório dele.
     *
     * Uma credencial que não puder ser removida é registrada e não interrompe a remoção: parar no
     * meio deixaria o workspace pela metade, que é pior do que uma senha órfã no cofre.
     *
     * @return os identificadores dos bancos cuja senha permaneceu no cofre.
     */
    fun erase(workspace: Workspace): List<String> {
        val remaining = workspace.datasources.mapNotNull { profile ->
            runCatching { credentials.remove(CredentialKey(workspace.id, profile.id), profile.user) }
                .fold(onSuccess = { null }, onFailure = { failure ->
                    LOG.warn("Password left in the safe for datasource '${profile.id}'.", failure)
                    profile.id
                })
        }
        store.delete(workspace.id)
        return remaining
    }

    private companion object {
        val LOG = Logger.getInstance(WorkspaceRemoval::class.java)
    }
}
