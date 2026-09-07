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

/** O catálogo de inspeções habilitadas e o perfil de onde ele veio. */
data class InspectionCatalog(
    val origin: InspectionProfileOrigin,
    val inspections: List<InspectionRecord>,
)
