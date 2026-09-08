package io.prumo.mcp.quality

/**
 * De onde saiu o catálogo.
 *
 * @property profileName nome do perfil corrente, como a IDE o apresenta.
 * @property scope nível em que esse perfil está declarado.
 */
data class InspectionProfileOrigin(
    val profileName: String,
    val scope: Scope,
) {
    /**
     * Nível de um perfil de inspeções.
     *
     * [PROJECT] é o perfil que acompanha o projeto e vale para quem clonar o repositório;
     * [APPLICATION] é o da instalação, e muda com quem abriu a IDE.
     */
    enum class Scope { PROJECT, APPLICATION }
}

/**
 * Decide o nível do perfil corrente.
 *
 * A IDE materializa um perfil `Project Default` no gerenciador do projeto mesmo quando o projeto
 * não declara perfil nenhum — ele nasce copiado do perfil da aplicação. Por isso ser administrado
 * pelo projeto não basta: só há regra combinada quando existe arquivo de perfil versionado junto do
 * projeto.
 *
 * @param managedByProject o perfil corrente está entre os que o gerenciador do projeto administra.
 * @param hasVersionedProfile existe arquivo de perfil no diretório de configuração do projeto.
 */
fun inspectionProfileScope(
    managedByProject: Boolean,
    hasVersionedProfile: Boolean,
): InspectionProfileOrigin.Scope =
    if (managedByProject && hasVersionedProfile) {
        InspectionProfileOrigin.Scope.PROJECT
    } else {
        InspectionProfileOrigin.Scope.APPLICATION
    }

/** O catálogo de inspeções habilitadas e o perfil de onde ele veio. */
data class InspectionCatalog(
    val origin: InspectionProfileOrigin,
    val inspections: List<InspectionRecord>,
)

/**
 * O arquivo é um perfil de inspeções versionado.
 *
 * `profiles_settings.xml` fica de fora: ele diz qual perfil o projeto usa, e existe também em
 * projeto que não traz perfil nenhum.
 */
fun isVersionedProfileFile(fileName: String): Boolean =
    fileName.endsWith(".xml", ignoreCase = true) && !fileName.equals("profiles_settings.xml", ignoreCase = true)
