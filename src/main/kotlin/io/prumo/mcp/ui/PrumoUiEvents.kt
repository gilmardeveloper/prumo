package io.prumo.mcp.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/** Recebe o aviso de que o estado desenhado pela janela do Prumo mudou. */
interface PrumoStateListener {

    fun prumoStateChanged()
}

/**
 * Canal entre quem muda o estado e quem o desenha.
 *
 * Gravar workspace, instalar pack e trocar o idioma acontecem fora da janela — em diálogos, na tela
 * de configuração e em painéis de outro pacote. Publicar um evento evita que cada um desses pontos
 * conheça a janela pelo nome.
 *
 * O canal é de aplicação, não de projeto: workspace e idioma são compartilhados por todos os
 * projetos abertos na mesma IDE.
 */
object PrumoUiEvents {

    val STATE_CHANGED: Topic<PrumoStateListener> = Topic.create("Prumo state changed", PrumoStateListener::class.java)

    /** Avisa que o estado mudou. Seguro fora da thread de interface: quem assina reagenda. */
    fun publishStateChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(STATE_CHANGED).prumoStateChanged()
    }
}
