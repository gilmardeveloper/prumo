package io.prumo.mcp.documentation

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A extração de PDF.
 *
 * O que estes testes protegem é a coordenada de página e a diferença entre "não tem texto" e "não
 * diz nada": a segunda faria o cliente concluir que o documento é vazio e desistir dele.
 */
class PdfExtractorTest {

    @Test
    fun `cada linha sabe de que pagina saiu`(@TempDir root: Path) {
        val arquivo = pdf(root, listOf("Rubrica 662 do evento S-1200", "Segunda pagina do documento"))

        val documento = PdfExtractor.extract(arquivo)

        assertEquals(SourceCoordinate.Page(1), documento.lines.first().coordinate)
        assertEquals(SourceCoordinate.Page(2), documento.lines.last().coordinate)
        assertTrue(documento.lines.first().text.contains("Rubrica 662"), documento.lines.first().text)
        assertTrue(documento.lines.last().text.contains("Segunda pagina"), documento.lines.last().text)
    }

    @Test
    fun `a janela recorta o documento extraido preservando a pagina`(@TempDir root: Path) {
        val arquivo = pdf(root, listOf("primeira", "segunda", "terceira"))

        val janela = PdfExtractor.extract(arquivo).window(firstLine = 2, maxLines = 1)

        assertEquals(1, janela.lines.size)
        assertEquals(SourceCoordinate.Page(2), janela.lines.single().coordinate)
        assertTrue(janela.truncated)
    }

    /** Página que é imagem não tem texto para extrair, e isso não é o mesmo que documento vazio. */
    @Test
    fun `pdf sem camada de texto e recusado em voz alta`(@TempDir root: Path) {
        val arquivo = root.resolve("digitalizado.pdf")
        PDDocument().use { documento ->
            repeat(3) { documento.addPage(PDPage()) }
            documento.save(arquivo.toFile())
        }

        val falha = assertThrows<DocumentationReadException> { PdfExtractor.extract(arquivo) }

        assertTrue(falha.message.orEmpty().contains("no text layer"), falha.message.orEmpty())
        assertTrue(falha.message.orEmpty().contains("3 page"), falha.message.orEmpty())
    }

    @Test
    fun `arquivo que nao e pdf nao e aberto como pdf`(@TempDir root: Path) {
        val arquivo = root.resolve("texto.pdf")
        Files.writeString(arquivo, "isto nao e um pdf")

        val falha = assertThrows<DocumentationReadException> { PdfExtractor.extract(arquivo) }

        assertTrue(falha.message.orEmpty().contains("could not open"), falha.message.orEmpty())
    }

    @Test
    fun `a familia reconhece a extensao que abre`(@TempDir root: Path) {
        assertTrue(PdfExtractor.handles(root.resolve("manual.PDF")))
        assertFalse(PdfExtractor.handles(root.resolve("planilha.xlsx")))
    }

    /**
     * Sonda sobre um PDF real de campo, com os números que a investigação mediu. Pulada quando o
     * arquivo não está na máquina, porque ele vive fora deste repositório.
     */
    @Test
    fun `o pdf real de campo sai com pagina e texto`() {
        val real = Path.of(
            "C:/projetos-seplag/folha-esocial/docs/nota-tecnica-s-1-3-06-2026-rev-pdf/" +
                "Leiautes do eSocial v. S-1.3 - Anexo II - Regras (cons. até NT 06.2026 rev.).pdf",
        )
        assumeTrue(Files.exists(real), "PDF de campo ausente nesta máquina")

        val documento = PdfExtractor.extract(real)
        val paginas = documento.lines.map { (it.coordinate as SourceCoordinate.Page).number }

        assertTrue(documento.lines.size > 100, "linhas: ${documento.lines.size}")
        assertEquals(paginas.sorted(), paginas, "as páginas saíram fora de ordem")
        assertEquals(1, paginas.first())
    }

    private fun pdf(root: Path, paginas: List<String>): Path {
        val arquivo = root.resolve("documento.pdf")
        PDDocument().use { documento ->
            paginas.forEach { texto ->
                val pagina = PDPage()
                documento.addPage(pagina)
                PDPageContentStream(documento, pagina).use { fluxo ->
                    fluxo.beginText()
                    fluxo.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    fluxo.newLineAtOffset(72f, 720f)
                    fluxo.showText(texto)
                    fluxo.endText()
                }
            }
            documento.save(arquivo.toFile())
        }
        return arquivo
    }
}
