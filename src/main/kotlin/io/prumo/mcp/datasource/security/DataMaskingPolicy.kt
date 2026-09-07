package io.prumo.mcp.datasource.security

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class MaskingRule {
    ALLOW,
    MASK,
}

/**
 * O que sai de uma coluna consultada.
 *
 * Coluna cujo nome anuncia segredo volta mascarada mesmo sem configuração alguma. O usuário pode
 * marcar outras colunas como sensíveis, e não pode desmarcar essas.
 *
 * Os valores possíveis são `ALLOW` e `MASK`.
 */
@Serializable
data class DataMaskingPolicy(
    val columns: Map<String, MaskingRule> = emptyMap(),
) {

    fun ruleFor(columnName: String): MaskingRule {
        val normalized = columnName.lowercase(Locale.ROOT)
        if (ALWAYS_MASKED.any { ColumnNameMatcher.matches(normalized, it) }) {
            return MaskingRule.MASK
        }
        return columns.entries
            .firstOrNull { it.key.lowercase(Locale.ROOT) == normalized }
            ?.value
            ?: MaskingRule.ALLOW
    }

    /** Mascara se **qualquer** um dos nomes que identificam a coluna anunciar segredo. */
    fun ruleFor(columnNames: Collection<String>): MaskingRule =
        if (columnNames.any { ruleFor(it) == MaskingRule.MASK }) MaskingRule.MASK else MaskingRule.ALLOW

    fun apply(columnName: String, value: String?): String? =
        when (ruleFor(columnName)) {
            MaskingRule.MASK -> if (value == null) null else MASKED
            MaskingRule.ALLOW -> value
        }

    companion object {
        const val MASKED = "[masked]"

        val NONE = DataMaskingPolicy()

        /**
         * Fragmentos de nome que sempre mascaram. A lista é de fragmento, não de nome exato:
         * `senha_atual`, `user_password` e `api_key_hash` precisam cair aqui. O fragmento vale como
         * segmento do nome, não como pedaço de palavra: `secretaria` não é `secret`.
         */
        private val ALWAYS_MASKED = listOf(
            "password", "passwd", "senha", "secret", "token", "apikey", "api_key",
            "credential", "credencial", "private_key", "privatekey", "passphrase",
            "client_secret", "access_key", "session_key",
        )
    }
}
