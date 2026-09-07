package io.prumo.mcp.ui

import com.intellij.openapi.project.Project

/**
 * As abas da janela do Prumo, na ordem em que aparecem.
 *
 * Ponto único de entrada para uma superfície nova: a aba nasce aqui e o resto da janela não muda.
 * Cada chamada devolve instâncias novas — a janela é remontada por inteiro a cada mudança de
 * estado, e reaproveitar aba já descartada usaria componente morto.
 */
object PrumoTabRegistry {

    fun tabsFor(project: Project): List<PrumoTab> = listOf(
        WorkspaceTab(project),
    )
}
