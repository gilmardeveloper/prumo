package io.prumo.mcp.ui

import com.intellij.openapi.Disposable
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Uma aba da janela do Prumo.
 *
 * Cada aba responde por um domínio e desenha só o que é dela. É a unidade que o registro monta e a
 * janela descarta: recurso vivo criado pela aba — conexão, observador, tarefa em segundo plano —
 * deve ser liberado em [dispose], que a plataforma chama quando o conteúdo sai da janela.
 */
interface PrumoTab : Disposable {

    /** Estável entre execuções. Identifica a aba no registro e nos testes, e não vai para a tela. */
    val id: String

    /** Chave do título no `PrumoBundle`. O texto é resolvido a cada montagem, nunca guardado. */
    val titleKey: String

    /** Ícone da aba, ou `null` para aba sem ícone. */
    val icon: Icon?

    /** Monta o conteúdo da aba. Chamado uma vez por montagem da janela. */
    fun createComponent(): JComponent

    override fun dispose() = Unit
}
