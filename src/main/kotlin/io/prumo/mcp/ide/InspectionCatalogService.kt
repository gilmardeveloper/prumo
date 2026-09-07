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
import io.prumo.mcp.quality.inspectionProfileScope
import io.prumo.mcp.quality.inspectionRecord
import io.prumo.mcp.quality.isVersionedProfileFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

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
                scope = inspectionProfileScope(
                    managedByProject = manager.profiles.any { it === profile },
                    hasVersionedProfile = hasVersionedProfile(project),
                ),
            ),
            inspections = profile.getAllEnabledInspectionTools(project)
                .map { tools: Tools -> record(tools.defaultState) },
        )
    }

    /**
     * Existe perfil de inspeções gravado junto do projeto.
     *
     * Procura no diretório de configuração do projeto, ao lado do arquivo que a IDE guarda ali —
     * `.idea/inspectionProfiles` no formato de diretório. Projeto no formato antigo, de arquivo
     * `.ipr` único, não tem esse diretório e é lido como perfil da aplicação.
     */
    private fun hasVersionedProfile(project: Project): Boolean {
        val settings = project.projectFilePath?.let(Path::of)?.parent ?: return false
        val directory = settings.resolve(InspectionProfileManager.INSPECTION_DIR)
        if (!Files.isDirectory(directory)) {
            return false
        }
        return Files.list(directory).use { entries ->
            entries.anyMatch { isVersionedProfileFile(it.name) }
        }
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
