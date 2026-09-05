package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.ide.IdeContextService
import io.prumo.mcp.policy.PolicyAction

/**
 * O que a IDE sabe sobre onde o desenvolvedor está.
 *
 * Informa o cursor, a seleção e o símbolo que os contém, sempre dentro da fronteira do workspace.
 */
class IdeToolset : McpToolset {

    @McpTool(name = GET_CURRENT_CONTEXT_TOOL)
    @McpDescription(
        "Returns where the developer is right now: the file being edited as a repository id plus a " +
            "relative path, the caret position, the selection and the symbols containing it. " +
            "When the open file belongs to no repository of this workspace, only that fact is reported.",
    )
    suspend fun getCurrentContext(): IdeContextResponse =
        prumoToolCall(
            GET_CURRENT_CONTEXT_TOOL,
            "ide.get_current_context",
            PolicyAction.READ_REPOSITORY,
        ) { call ->
            RepositoryReports.ideContext(call.context, IdeContextService.currentEditor(call.project))
        }

    private companion object {
        const val GET_CURRENT_CONTEXT_TOOL = "prumo_ide_get_current_context"
    }
}
