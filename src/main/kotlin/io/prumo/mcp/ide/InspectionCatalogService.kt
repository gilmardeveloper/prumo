package io.prumo.mcp.ide

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.ScopeToolState
import com.intellij.codeInspection.ex.Tools
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProfileManager
import io.prumo.mcp.quality.InspectionRecord
import io.prumo.mcp.quality.inspectionRecord

/**
 * As inspeções habilitadas no perfil corrente do projeto.
 *
 * Lê apenas metadado declarado: nenhuma inspeção é instanciada, nenhum arquivo é aberto e nenhuma
 * análise é executada.
 */
object InspectionCatalogService {

    fun read(project: Project): List<InspectionRecord> {
        val profile: InspectionProfileImpl = InspectionProfileManager.getInstance(project).currentProfile
        return profile.getAllEnabledInspectionTools(project).map { tools: Tools -> record(tools.defaultState) }
    }

    private fun record(state: ScopeToolState): InspectionRecord {
        val wrapper: InspectionToolWrapper<*, *> = state.tool
        return inspectionRecord(
            shortName = wrapper.shortName,
            displayName = wrapper.displayName,
            group = wrapper.groupDisplayName,
            severity = state.level.name,
            language = wrapper.language,
            enabledByDefault = wrapper.isEnabledByDefault,
        )
    }
}
