package io.prumo.mcp.documentation

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name

/**
 * Extrai o texto de um PDF, página a página.
 *
 * O texto de um PDF não está no arquivo como texto: está como índice de glifo de fontes embutidas, e
 * só o mapa `ToUnicode` de cada fonte o traduz de volta. Por isso a extração delega ao PDFBox em vez
 * de ler o arquivo à mão.
 *
 * PDF digitalizado — página que é imagem, sem camada de texto — é recusado em voz alta, e não
 * devolvido vazio: resposta vazia faria o cliente concluir que o documento não diz nada.
 */
object PdfExtractor {

    private const val EXTENSION = "pdf"

    /** Teto de páginas de um único documento. */
    private const val MAX_PAGES = 5_000

    fun handles(file: Path): Boolean =
        file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) == EXTENSION

    /**
     * O conteúdo do PDF, uma linha por linha de página, cada uma sabendo de que página saiu.
     *
     * @throws DocumentationReadException quando o arquivo não abre, exige senha, passa do teto de
     *   páginas ou não tem camada de texto.
     */
    fun extract(file: Path): ExtractedDocument =
        try {
            Loader.loadPDF(file.toFile()).use { documento -> ExtractedDocument(linesOf(documento, file)) }
        } catch (failure: DocumentationReadException) {
            throw failure
        } catch (failure: InvalidPasswordException) {
            throw DocumentationReadException(
                "'${file.name}' is password protected, and Prumo does not ask for the password.",
            )
        } catch (failure: IOException) {
            throw DocumentationReadException("Prumo could not open '${file.name}' as a PDF document.")
        }

    private fun linesOf(documento: PDDocument, file: Path): List<ExtractedLine> {
        if (documento.numberOfPages > MAX_PAGES) {
            throw DocumentationReadException(
                "'${file.name}' has ${documento.numberOfPages} pages, beyond the $MAX_PAGES Prumo reads " +
                    "from one document.",
            )
        }

        val stripper = PDFTextStripper()
        val linhas = mutableListOf<ExtractedLine>()
        for (pagina in 1..documento.numberOfPages) {
            stripper.startPage = pagina
            stripper.endPage = pagina
            stripper.getText(documento)
                .lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .forEach { linhas.add(ExtractedLine(SourceCoordinate.Page(pagina), it)) }
        }

        if (linhas.isEmpty()) {
            throw DocumentationReadException(
                "'${file.name}' has no text layer: its ${documento.numberOfPages} page(s) carry images, " +
                    "not text, so there is nothing for Prumo to extract.",
            )
        }
        return linhas
    }
}
