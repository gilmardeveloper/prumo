package io.prumo.mcp.documentation

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Sonda de classpath do PDFBox, no mesmo molde da que existe para o MVStore.
 *
 * O que ela responde é se a biblioteca carrega e extrai texto dentro do ambiente do plugin. Falha
 * aqui derruba a família de PDF antes de qualquer código de produto depender dela.
 */
class PdfBoxClasspathProbeTest {

    @Test
    fun `o PDFBox carrega e extrai o texto que foi escrito`(@TempDir root: Path) {
        val arquivo = root.resolve("sonda.pdf")
        PDDocument().use { documento ->
            val pagina = PDPage()
            documento.addPage(pagina)
            PDPageContentStream(documento, pagina).use { fluxo ->
                fluxo.beginText()
                fluxo.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                fluxo.newLineAtOffset(72f, 720f)
                fluxo.showText("Rubrica 662 do evento S-1200")
                fluxo.endText()
            }
            documento.save(arquivo.toFile())
        }

        val texto = Loader.loadPDF(arquivo.toFile()).use { PDFTextStripper().getText(it) }

        assertTrue(texto.contains("Rubrica 662 do evento S-1200"), texto)
    }

    @Test
    fun `a contagem de paginas chega junto com o texto`(@TempDir root: Path) {
        val arquivo = root.resolve("duas.pdf")
        PDDocument().use { documento ->
            repeat(2) { documento.addPage(PDPage()) }
            documento.save(arquivo.toFile())
        }

        Loader.loadPDF(arquivo.toFile()).use { documento ->
            assertEquals(2, documento.numberOfPages)
        }
    }
}
