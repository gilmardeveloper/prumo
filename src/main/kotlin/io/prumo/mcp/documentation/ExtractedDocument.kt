package io.prumo.mcp.documentation

/**
 * De onde, dentro do documento, uma linha de texto veio.
 *
 * A coordenada acompanha a linha até a resposta ao cliente: é por ela que a IA volta à fonte, e é
 * ela que a busca por trecho devolve no lugar do documento inteiro.
 */
sealed interface SourceCoordinate {

    /** Como a coordenada é dita ao cliente. */
    val label: String

    /** Página de um PDF, contada a partir de 1. */
    data class Page(val number: Int) : SourceCoordinate {
        override val label: String get() = "page $number"
    }

    /** Linha de uma planilha, contada a partir de 1 dentro da aba. */
    data class Row(val sheet: String, val number: Int) : SourceCoordinate {
        override val label: String get() = "sheet '$sheet' row $number"
    }

    /** Parágrafo de um documento de texto, contado a partir de 1. */
    data class Paragraph(val number: Int) : SourceCoordinate {
        override val label: String get() = "paragraph $number"
    }

    /** Slide de uma apresentação, contado a partir de 1. */
    data class Slide(val number: Int) : SourceCoordinate {
        override val label: String get() = "slide $number"
    }
}

/** Uma linha de texto extraída, com a coordenada de onde ela saiu. */
data class ExtractedLine(val coordinate: SourceCoordinate, val text: String)

/**
 * O recorte pedido de um documento extraído.
 *
 * Espelha o contrato que `DocumentationReader` já publica — primeira linha, última, total e se
 * sobrou — para que formato binário e texto puro respondam do mesmo jeito.
 */
data class ExtractedWindow(
    val lines: List<ExtractedLine>,
    val firstLine: Int,
    val lastLine: Int,
    val totalLines: Int,
    val truncated: Boolean,
)

/**
 * O conteúdo de um documento binário depois de descartado o que não é texto.
 *
 * O documento é uma lista de linhas porque é assim que o produto já entrega arquivo: a janela é
 * contada em linhas, e cada linha carrega a sua origem. Estilo, tema, relação, propriedade e
 * desenho não chegam aqui.
 */
data class ExtractedDocument(val lines: List<ExtractedLine>) {

    /**
     * O recorte que começa em [firstLine] e traz no máximo [maxLines] linhas.
     *
     * Pedido além do fim devolve recorte vazio apontando para o fim, e não erro: o cliente que
     * paginou até o fim precisa saber que acabou, não que errou.
     *
     * @throws IllegalArgumentException quando [firstLine] ou [maxLines] é menor que 1.
     */
    fun window(firstLine: Int, maxLines: Int): ExtractedWindow {
        require(firstLine >= 1) { "firstLine must be 1 or greater." }
        require(maxLines >= 1) { "maxLines must be 1 or greater." }

        val from = (firstLine - 1).coerceAtMost(lines.size)
        val slice = lines.drop(from).take(maxLines)
        return ExtractedWindow(
            lines = slice,
            firstLine = if (slice.isEmpty()) firstLine else from + 1,
            lastLine = from + slice.size,
            totalLines = lines.size,
            truncated = from + slice.size < lines.size,
        )
    }
}
