package io.prumo.mcp.datasource.security

import java.util.Locale

/**
 * Descobre quais colunas calculadas de um statement derivam de coluna de nome sensível.
 *
 * Existe por causa de coluna calculada: `length(txt_senha) AS n` chega ao driver com rótulo `n` e
 * sem nome de coluna de origem, então nem o rótulo nem o metadado JDBC denunciam a origem.
 *
 * A varredura é sobre o texto do statement, e não sobre a árvore sintática, de propósito: uma
 * resposta textual basta para quem chama, e o custo de errar é conhecido — erra-se para o lado de
 * mascarar. Quando a lista de seleção não pode ser mapeada com segurança para as posições de saída,
 * [sensitivePositions] devolve `null`, e quem chama trata todas as colunas calculadas como
 * sensíveis.
 */
object SensitiveColumnScanner {

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** `<coluna> AS <apelido>`, com a coluna possivelmente qualificada por tabela. */
    /** Operador que combina o resultado de mais de um `SELECT`. */
    private val SET_OPERATOR = Regex("""\b(union|except|intersect)\b""", RegexOption.IGNORE_CASE)

    /** Função que empacota a linha inteira, ou conversão de um apelido de linha para texto. */
    private val ROW_SERIALIZER = Regex(
        """\b(row_to_json|to_json|to_jsonb|jsonb_agg|json_agg|json_build_object)\s*\(|::\s*(text|json|jsonb)""",
        RegexOption.IGNORE_CASE,
    )

    private val ALIAS = Regex(
        """(?:[A-Za-z_][A-Za-z0-9_]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s+as\s+([A-Za-z_][A-Za-z0-9_]*)""",
        RegexOption.IGNORE_CASE,
    )

    fun touchesSensitiveColumn(sql: String, masking: DataMaskingPolicy): Boolean =
        IDENTIFIER.findAll(sql.lowercase(Locale.ROOT))
            .any { masking.ruleFor(it.value) == MaskingRule.MASK }

    /**
     * Posições de saída, começando em 1, cujo item de seleção referencia identificador sensível.
     *
     * @param columnCount quantas colunas o resultado trouxe, usado para confirmar o mapeamento.
     * @return as posições sensíveis, ou `null` quando a lista de seleção não pôde ser mapeada —
     *   statement que não é um `SELECT` simples, expansão por `*`, ou contagem de itens diferente da
     *   contagem de colunas do resultado.
     */
    fun sensitivePositions(sql: String, masking: DataMaskingPolicy, columnCount: Int): Set<Int>? {
        val items = selectItems(sql) ?: return null
        if (items.size != columnCount) {
            return null
        }
        if (items.any { it.trim() == "*" || it.trim().endsWith(".*") }) {
            return null
        }
        return items.withIndex()
            .filter { (_, item) -> touchesSensitiveColumn(item, masking) }
            .map { (index, _) -> index + 1 }
            .toSet()
    }

    /**
     * Posições cujo item de seleção é uma agregação que não devolve o valor de origem.
     *
     * `count`, `sum`, `avg` e `length` produzem um número sobre o conjunto: nenhum deles carrega o
     * documento de ninguém, e escondê-los inutiliza a análise sem proteger nada — uma contagem de
     * CPF preenchido é resposta de qualidade de cadastro, não dado pessoal.
     *
     * `min`, `max`, `string_agg` e `array_agg` ficam de fora de propósito: devolvem valores de
     * origem, mesmo sendo agregações.
     */
    fun safeAggregatePositions(sql: String, columnCount: Int): Set<Int> {
        val items = selectItems(sql) ?: return emptySet()
        if (items.size != columnCount) {
            return emptySet()
        }
        return items.withIndex()
            .filter { (_, item) -> isSafeAggregate(item) }
            .map { (index, _) -> index + 1 }
            .toSet()
    }

    private fun isSafeAggregate(item: String): Boolean {
        val normalized = item.trim().lowercase(Locale.ROOT)
        if (VALUE_RETURNING.any { normalized.contains(it) }) {
            return false
        }
        return SAFE_AGGREGATES.any { normalized.startsWith(it) }
    }

    /**
     * O statement combina resultados com `UNION`, `EXCEPT` ou `INTERSECT`.
     *
     * A lista de seleção do primeiro braço não descreve os demais: `SELECT txt_sexo … UNION ALL
     * SELECT num_cpf …` entrega o CPF sob a classificação da primeira coluna. Havendo operador de
     * conjunto, nenhuma posição pode ser mapeada com confiança.
     */
    fun combinesResultSets(sql: String): Boolean = SET_OPERATOR.containsMatchIn(sql)

    /**
     * A coluna serializa uma linha inteira, e não um campo.
     *
     * `row_to_json(t)`, `to_jsonb(t)` e `t::text` empacotam todas as colunas da origem num único
     * valor: nenhum nome de coluna sobra na expressão, e o conteúdo é o registro completo.
     */
    fun serializesWholeRow(item: String): Boolean = ROW_SERIALIZER.containsMatchIn(item)

    /** Itens da lista de seleção por posição, ou `null` quando a lista não pôde ser mapeada. */
    fun selectItemsOrNull(sql: String, columnCount: Int): List<String>? =
        selectItems(sql)?.takeIf { it.size == columnCount }

    /**
     * Mapeia cada apelido do statement à coluna que ele renomeia.
     *
     * Uma subconsulta que faz `SELECT txt_email AS a` e uma consulta externa que faz `upper(t.a)`
     * deixam o driver sem coluna de origem e o rótulo sem nada que denuncie o dado: o identificador
     * que sobrou é `a`. Sem resolver o apelido de volta, a expressão passa limpa.
     */
    fun aliasOrigins(sql: String): Map<String, String> =
        ALIAS.findAll(sql)
            .associate { it.groupValues[2].lowercase(Locale.ROOT) to it.groupValues[1].lowercase(Locale.ROOT) }

    /** Todos os identificadores citados no statement, para o recuo em que nada mais é confiável. */
    fun allIdentifiers(sql: String): List<String> =
        IDENTIFIER.findAll(sql).map { it.value }.distinct().toList()

    /**
     * Identificadores citados por cada item da lista de seleção, na ordem das colunas de saída.
     *
     * Serve à coluna calculada, cujo metadado JDBC não denuncia a origem: `substr(num_cpf, 1, 9)`
     * chega com rótulo `substr` e sem coluna de origem, e só a leitura da expressão revela de que
     * coluna o valor derivou.
     *
     * @return um conjunto de identificadores por posição, ou `null` quando a lista não pôde ser
     *   mapeada com segurança.
     */
    fun identifiersByPosition(sql: String, columnCount: Int): List<List<String>>? {
        val items = selectItems(sql) ?: return null
        if (items.size != columnCount || items.any { it.trim() == "*" || it.trim().endsWith(".*") }) {
            return null
        }
        return items.map { item -> IDENTIFIER.findAll(item).map { it.value }.toList() }
    }

    /**
     * Divide a lista de seleção do `SELECT` mais externo nos seus itens de topo.
     *
     * @return os itens, ou `null` quando o statement não é um `SELECT` de lista reconhecível.
     */
    private fun selectItems(sql: String): List<String>? {
        val normalized = sql.trim()
        if (!normalized.startsWith("select", ignoreCase = true)) {
            return null
        }

        var cursor = "select".length
        val distinct = normalized.regionMatches(cursor, " distinct", 0, " distinct".length, ignoreCase = true)
        if (distinct) {
            cursor += " distinct".length
        }

        val items = mutableListOf<String>()
        val item = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var index = cursor

        while (index < normalized.length) {
            val char = normalized[index]
            when {
                quote != null -> {
                    if (char == quote) quote = null
                    item.append(char)
                }

                char == '\'' || char == '"' -> {
                    quote = char
                    item.append(char)
                }

                char == '(' -> {
                    depth++
                    item.append(char)
                }

                char == ')' -> {
                    depth--
                    if (depth < 0) return null
                    item.append(char)
                }

                depth == 0 && char == ',' -> {
                    items.add(item.toString())
                    item.clear()
                }

                depth == 0 && isBoundary(normalized, index) -> {
                    items.add(item.toString())
                    return items.takeIf { list -> list.none { it.isBlank() } }
                }

                else -> item.append(char)
            }
            index++
        }

        if (quote != null || depth != 0) {
            return null
        }
        items.add(item.toString())
        return items.takeIf { list -> list.none { it.isBlank() } }
    }

    /** Palavra que encerra a lista de seleção no nível mais externo. */
    private fun isBoundary(sql: String, index: Int): Boolean {
        if (index > 0 && (sql[index - 1].isLetterOrDigit() || sql[index - 1] == '_')) {
            return false
        }
        return BOUNDARIES.any { keyword ->
            sql.regionMatches(index, keyword, 0, keyword.length, ignoreCase = true) &&
                (index + keyword.length >= sql.length || !isIdentifierPart(sql[index + keyword.length]))
        }
    }

    private fun isIdentifierPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private val BOUNDARIES = listOf("from", "where", "group", "order", "limit", "having", "union", "offset")

    /**
     * Nomes das agregações que voltam completas, para exibição.
     *
     * A orientação entregue ao cliente é gerada desta lista, e não escrita à parte: acrescentar uma
     * função aqui muda o texto junto, sem depender de alguém lembrar de atualizá-lo.
     */
    val SAFE_AGGREGATE_NAMES: List<String>
        get() = SAFE_AGGREGATES.map { it.removeSuffix("(").removeSuffix("_") }.distinct()

    /** Agregações e funções que produzem um número sobre o conjunto, nunca um valor de origem. */
    private val SAFE_AGGREGATES = listOf(
        "count(", "sum(", "avg(", "stddev", "variance", "var_", "length(", "char_length(",
        "octet_length(", "bit_length(",
    )

    /** Agregações que devolvem um valor de origem, e por isso não são seguras. */
    private val VALUE_RETURNING = listOf("min(", "max(", "string_agg(", "array_agg(", "json_agg(")
}
