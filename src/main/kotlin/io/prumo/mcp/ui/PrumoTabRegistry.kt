package io.prumo.mcp.ui

import com.intellij.openapi.project.Project
import io.prumo.mcp.ui.tab.ActivityTab
import io.prumo.mcp.ui.tab.DataSourcesTab
import io.prumo.mcp.ui.tab.KnowledgeTab
import io.prumo.mcp.ui.tab.MemoryTab
import io.prumo.mcp.ui.tab.RepositoriesTab
import io.prumo.mcp.ui.tab.WorkspaceTab

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
        RepositoriesTab(project),
        DataSourcesTab(project),
        KnowledgeTab(project),
        MemoryTab(project),
        ActivityTab(project),
    )
}
