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
 * Existe uma regra que **não depende de configuração**: coluna cujo nome anuncia segredo — senha,
 * token, chave, hash de credencial — volta mascarada, ainda que ninguém tenha configurado nada. O
 * usuário pode marcar outras colunas como sensíveis; não pode desmarcar essas, porque o valor
 * padrão errado aqui é o que vaza credencial de produção para dentro de um contexto de LLM.
 *
 * `DENY` (recusar a consulta inteira quando toca a coluna) fica para depois do MVP; o que existe
 * agora é `ALLOW` e `MASK`.
 */
@Serializable
data class DataMaskingPolicy(
    val columns: Map<String, MaskingRule> = emptyMap(),
) {

    fun ruleFor(columnName: String): MaskingRule {
        val normalized = columnName.lowercase(Locale.ROOT)
        if (ALWAYS_MASKED.any { normalized.contains(it) }) {
            return MaskingRule.MASK
        }
        return columns.entries
            .firstOrNull { it.key.lowercase(Locale.ROOT) == normalized }
            ?.value
            ?: MaskingRule.ALLOW
    }

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
         * `senha_atual`, `user_password` e `api_key_hash` precisam cair aqui.
         */
        private val ALWAYS_MASKED = listOf(
            "password", "passwd", "senha", "secret", "token", "apikey", "api_key",
            "credential", "credencial", "private_key", "privatekey", "passphrase",
            "client_secret", "access_key", "session_key",
        )
    }
}
