package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.ide.InspectionCatalogService
import io.prumo.mcp.quality.InspectionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * O que esta IDE sabe procurar.
 *
 * Lista as inspeções registradas e habilitadas no perfil corrente. Não executa nenhuma delas e não
 * alcança arquivo algum.
 */
class QualityToolset : McpToolset {

    @McpTool(name = LIST_INSPECTIONS_TOOL)
    @McpDescription(
        "Use this tool to learn what this IDE knows how to look for: the code inspections " +
            "registered and enabled in the current profile of the open project. Call it when the " +
            "request asks which rules, checks or inspections exist, or before proposing a rule the " +
            "IDE may already have. This tool runs no inspection and reads no file. Finding the " +
            "problems of one specific file is a different job, and get_file_problems is the tool " +
            "for it. Registered and enabled is not the same as applicable: whether an inspection " +
            "would run on a given file depends on the file, and this catalog does not say. The " +
            "counts describe this installation and its plugins, not the Prumo product, so they " +
            "change with the IDE edition and the plugins installed. shortName is stable and always " +
            "English; displayName follows the IDE language.",
    )
    suspend fun listInspections(
        @McpDescription(
            "Keep only inspections declaring this language, such as \"kotlin\", \"JAVA\" or " +
                "\"yaml\". Inspections that declare no language are left out.",
        )
        language: String? = null,
        @McpDescription(
            "Keep only inspections configured at this severity in the current profile, such as " +
                "\"WARNING\", \"WEAK WARNING\", \"ERROR\" or \"TYPO\". Severities are not a closed " +
                "vocabulary: a plugin may register its own.",
        )
        severity: String? = null,
        @McpDescription("Keep only inspections shown under this group, spelled as displayName groups them.")
        group: String? = null,
        @McpDescription(
            "How many inspections to return at most. The counts by severity and by group always " +
                "cover the whole match, not just the returned window.",
        )
        maxResults: Int = 50,
    ): InspectionCatalogResponse =
        prumoToolCall(
            LIST_INSPECTIONS_TOOL,
            "quality.list_inspections",
            PolicyAction.READ_QUALITY_CATALOG,
        ) { call ->
            val catalog = withContext(Dispatchers.IO) { InspectionCatalogService.read(call.project) }
            call.auditDetails["matchCount"] = catalog.size.toString()
            QualityReports.catalog(
                catalog = catalog,
                criteria = InspectionFilter(language = language, severity = severity, group = group),
                maxResults = maxResults,
            )
        }

    private companion object {
        const val LIST_INSPECTIONS_TOOL = "prumo_quality_list_inspections"
    }
}
