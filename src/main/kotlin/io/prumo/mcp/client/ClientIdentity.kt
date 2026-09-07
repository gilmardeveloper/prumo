package io.prumo.mcp.client

/**
 * Quem está do outro lado de uma chamada MCP.
 *
 * O nome e a versão são declarados pelo próprio cliente no `initialize`, então chegam como texto
 * livre: são saneados aqui antes de entrar na trilha ou em qualquer registro.
 */
data class ClientIdentity(
    val name: String,
    val version: String,
) {

    /** Forma estável para a trilha e para a autoria de um registro. */
    val label: String
        get() = if (version.isBlank()) name else "$name/$version"

    companion object {

        /** Usada quando o cliente não se identifica. Nunca é nulo, para não espalhar ausência. */
        val UNKNOWN = ClientIdentity("unknown", "")

        private const val MAX_LENGTH = 64

        fun of(name: String?, version: String?): ClientIdentity {
            val cleanName = clean(name) ?: return UNKNOWN
            return ClientIdentity(cleanName, clean(version).orEmpty())
        }

        private fun clean(value: String?): String? =
            value?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)
    }
}
