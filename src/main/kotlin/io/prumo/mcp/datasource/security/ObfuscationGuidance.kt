package io.prumo.mcp.datasource.security

/**
 * Orientação entregue ao cliente de IA junto do resultado de uma consulta.
 *
 * O produto já sabe o que a IA precisa — que agregação volta completa, que valor escondido não é
 * chave, se a proteção está ligada — e sem isto nada disso é dito por um canal que ela leia. É
 * complemento das travas, nunca substituto: toda garantia vive em código e está coberta por teste.
 *
 * O texto é afirmativo, e diz o que fazer. Enumerar as construções em que a proteção é mais
 * restritiva entregaria o caminho de contorno a quem não o tivesse procurado.
 */
object ObfuscationGuidance {

    /** Orientação para um resultado em que alguma coluna saiu com dado pessoal escondido. */
    fun forObfuscatedResult(): String =
        "Personal data here is partially hidden. ${aggregateSentence()} " +
            "A hidden value is not a key: different people can share one, so do not join on it."

    /** Orientação para um resultado que trouxe dado pessoal sem proteção, por escolha do desenvolvedor. */
    fun forUnprotectedResult(): String =
        "Personal data in this result is complete and unmasked: obfuscation is turned off for this " +
            "database. Treat these values as real personal data in whatever you produce from them."

    /** Orientação publicada na listagem de bancos, antes da primeira consulta. */
    fun forDatasource(obfuscated: Boolean): String =
        if (obfuscated) {
            "Personal data from this database comes back partially hidden; its structure comes back " +
                "whole. ${aggregateSentence()}"
        } else {
            "Personal data from this database comes back complete and unmasked."
        }

    /**
     * Frase sobre agregação, montada a partir da lista que decide o comportamento.
     *
     * Deriva de [SensitiveColumnScanner.SAFE_AGGREGATE_NAMES] para que o texto não possa divergir do
     * que o código faz.
     */
    private fun aggregateSentence(): String {
        val names = SensitiveColumnScanner.SAFE_AGGREGATE_NAMES
        return "Aggregates return real numbers — ${names.joinToString(", ")} — so use them for statistics."
    }
}
