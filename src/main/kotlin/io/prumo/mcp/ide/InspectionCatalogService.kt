package io.prumo.mcp.ide

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.ScopeToolState
import com.intellij.codeInspection.ex.Tools
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProfileManager
import io.prumo.mcp.quality.InspectionCatalog
import io.prumo.mcp.quality.InspectionProfileOrigin
import io.prumo.mcp.quality.InspectionRecord
import io.prumo.mcp.quality.inspectionRecord

/**
 * As inspeções habilitadas no perfil corrente do projeto, e o perfil de onde vieram.
 *
 * Lê apenas metadado declarado: nenhuma inspeção é instanciada, nenhum arquivo é aberto e nenhuma
 * análise é executada.
 */
object InspectionCatalogService {

    fun read(project: Project): InspectionCatalog {
        val manager = InspectionProfileManager.getInstance(project)
        val profile: InspectionProfileImpl = manager.currentProfile
        return InspectionCatalog(
            origin = InspectionProfileOrigin(
                profileName = profile.displayName ?: profile.name,
                scope = scopeOf(profile, manager),
            ),
            inspections = profile.getAllEnabledInspectionTools(project)
                .map { tools: Tools -> record(tools.defaultState) },
        )
    }

    /**
     * Decide se [profile] é do projeto ou da instalação.
     *
     * O gerenciador do projeto responde com o perfil versionado quando o projeto declara usar um, e
     * com um perfil da aplicação quando não declara. Só o primeiro caso está entre os perfis que o
     * próprio gerenciador do projeto administra.
     */
    private fun scopeOf(
        profile: InspectionProfileImpl,
        manager: InspectionProfileManager,
    ): InspectionProfileOrigin.Scope =
        if (manager.profiles.any { it === profile }) {
            InspectionProfileOrigin.Scope.PROJECT
        } else {
            InspectionProfileOrigin.Scope.APPLICATION
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
