package io.prumo.mcp.datasource.security

import java.util.Locale

/**
 * Descobre se um statement encosta em alguma coluna de nome sensível.
 *
 * Existe por causa de coluna calculada: `length(txt_senha) AS n` chega ao driver com rótulo `n` e
 * sem nome de coluna de origem, então nem o rótulo nem o metadado JDBC denunciam a origem.
 *
 * A varredura é sobre os identificadores do texto do statement, e não sobre a árvore sintática, de
 * propósito: mapear item de seleção para índice de saída deixa de valer assim que aparece um `*`, e
 * uma resposta por statement inteiro basta para quem chama. O efeito é errar para o lado de
 * mascarar — um literal que contenha `senha` também dispara —, e essa é a direção aceitável.
 */
object SensitiveColumnScanner {

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun touchesSensitiveColumn(sql: String, masking: DataMaskingPolicy): Boolean =
        IDENTIFIER.findAll(sql.lowercase(Locale.ROOT))
            .any { masking.ruleFor(it.value) == MaskingRule.MASK }
}
