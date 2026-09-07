package io.prumo.mcp.ui.pack

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.prumo.mcp.i18n.PrumoBundle

/**
 * Mostra ao usuário o resultado de uma operação de pack.
 *
 * A confirmação não pode viver num rótulo do painel: instalar, importar e remover remontam a janela
 * logo em seguida, e o rótulo é destruído antes de ser lido.
 */
internal fun notifyPack(project: Project, messageKey: String, vararg arguments: Any) {
    notify(project, NotificationType.INFORMATION, messageKey, arguments)
}

/** Aviso de operação que não aconteceu por decisão ou por falta de escolha do usuário. */
internal fun notifyPackWarning(project: Project, messageKey: String, vararg arguments: Any) {
    notify(project, NotificationType.WARNING, messageKey, arguments)
}

private fun notify(project: Project, type: NotificationType, messageKey: String, arguments: Array<out Any>) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(GROUP_ID)
        .createNotification(PrumoBundle.message(messageKey, *arguments), type)
        .notify(project)
}

/** Precisa ser igual ao `id` do `notificationGroup` declarado no descritor do plugin. */
private const val GROUP_ID = "Prumo MCP"
