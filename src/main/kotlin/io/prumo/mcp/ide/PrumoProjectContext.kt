package io.prumo.mcp.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.prumo.mcp.ui.PrumoStateListener
import io.prumo.mcp.ui.PrumoUiEvents
import io.prumo.mcp.workspace.application.WorkspaceResolution

/**
 * Guarda o workspace resolvido deste projeto enquanto ele não muda.
 *
 * Resolver custa uma varredura de todos os workspaces cadastrados mais a leitura dos cinco arquivos
 * de cada um. A janela tem várias abas, e sem isso cada uma pagaria a conta inteira por montagem.
 *
 * O cache só vale para a interface. As ferramentas MCP continuam resolvendo a cada chamada: o
 * arquivo do workspace pode mudar por fora desta IDE, e entregar contexto vencido a um cliente de
 * IA é pior do que relê-lo.
 */
@Service(Service.Level.PROJECT)
class PrumoProjectContext(private val project: Project) : Disposable {

    @Volatile
    private var cached: WorkspaceResolution? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            PrumoUiEvents.STATE_CHANGED,
            object : PrumoStateListener {
                override fun prumoStateChanged() = invalidate()
            },
        )
    }

    /** O workspace deste projeto, relido apenas depois de o estado mudar. */
    fun resolve(): WorkspaceResolution =
        cached ?: PrumoWorkspaceService.getInstance().resolve(project).also { cached = it }

    /** Descarta o que está guardado. Todo ponto que grava estado publica o evento que chama isto. */
    fun invalidate() {
        cached = null
    }

    override fun dispose() = invalidate()

    companion object {
        fun getInstance(project: Project): PrumoProjectContext = project.service()
    }
}
