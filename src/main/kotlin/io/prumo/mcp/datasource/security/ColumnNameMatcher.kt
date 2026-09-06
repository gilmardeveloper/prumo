package io.prumo.mcp.datasource.security

/**
 * Casa um fragmento de nome contra o nome de uma coluna, segmento a segmento.
 *
 * Os segmentos vêm da divisão por `_`, `-`, `.` e espaço. O fragmento casa quando os segmentos dele
 * aparecem em sequência na coluna, idênticos — aceitos o plural e a numeração no último, que é como
 * `senhas` e `senha2` aparecem. Fragmento no meio de um segmento nunca casa: `remuneracao` contém
 * `raca`, `secretaria` contém `secret` e `orgao` contém `rg`, e nenhuma das três é o dado que o
 * fragmento procura.
 */
object ColumnNameMatcher {

    private val SEPARATORS = charArrayOf('_', '-', '.', ' ')
    private val PLURAL_SUFFIXES = listOf("s", "es")

    fun matches(columnName: String, fragment: String): Boolean {
        val column = segmentsOf(columnName)
        val wanted = segmentsOf(fragment)
        if (wanted.isEmpty() || column.size < wanted.size) {
            return false
        }
        val last = wanted.size - 1
        return (0..column.size - wanted.size).any { start ->
            wanted.indices.all { offset ->
                val segment = column[start + offset]
                val part = wanted[offset]
                if (offset == last) matchesEnd(segment, part) else segment == part
            }
        }
    }

    private fun matchesEnd(segment: String, part: String): Boolean {
        if (segment == part || PLURAL_SUFFIXES.any { segment == part + it }) {
            return true
        }
        val rest = segment.removePrefix(part)
        return rest.length < segment.length && rest.isNotEmpty() && rest.all(Char::isDigit)
    }

    private fun segmentsOf(name: String): List<String> =
        name.split(*SEPARATORS).filter { it.isNotEmpty() }
}
