package io.prumo.mcp.workspace.application

import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore

/**
 * Banco cuja senha permaneceu no cofre depois da remoção do workspace, com a falha que a manteve.
 *
 * @property datasourceId identificador do banco dentro do workspace removido.
 * @property cause falha devolvida pelo cofre ao tentar apagar a senha.
 */
data class LeftoverCredential(val datasourceId: String, val cause: Throwable)

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
     * Uma credencial que não puder ser removida é devolvida e não interrompe a remoção: parar no
     * meio deixaria o workspace pela metade, que é pior do que uma senha órfã no cofre.
     *
     * @return os bancos cuja senha permaneceu no cofre, cada um com a falha que a manteve.
     */
    fun erase(workspace: Workspace): List<LeftoverCredential> {
        val remaining = workspace.datasources.mapNotNull { profile ->
            runCatching { credentials.remove(CredentialKey(workspace.id, profile.id), profile.user) }
                .fold(
                    onSuccess = { null },
                    onFailure = { failure -> LeftoverCredential(profile.id, failure) },
                )
        }
        store.delete(workspace.id)
        return remaining
    }
}
