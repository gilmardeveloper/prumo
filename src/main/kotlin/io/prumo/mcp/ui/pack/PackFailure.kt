package io.prumo.mcp.ui.pack

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.prumo.mcp.i18n.PrumoBundle

/**
 * Mostra ao usuário a falha de uma operação de pack.
 *
 * Exceção lançada de dentro de um ouvinte de botão não chega à tela: a IDE a recolhe como erro
 * interno do plugin. Toda ação de pack passa por aqui.
 */
internal fun showPackFailure(project: Project, failure: Throwable, titleKey: String) {
    Messages.showErrorDialog(
        project,
        failure.message ?: PrumoBundle.message("pack.exchange.unknownError"),
        PrumoBundle.message(titleKey),
    )
}
