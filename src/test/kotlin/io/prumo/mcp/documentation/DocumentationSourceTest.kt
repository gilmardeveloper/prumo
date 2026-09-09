package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DocumentationSourceTest {

    @Test
    fun `identificador invalido e recusado`() {
        listOf("../fora", "a/b", "", "com espaco").forEach { id ->
            assertThrows(IllegalArgumentException::class.java, {
                DocumentationSource(id, "Regras", DocumentationKind.FILE, "/docs/regras.md")
            }, id)
        }
    }

    @Test
    fun `a autoridade padrao e referencia, nao oficial`() {
        val source = DocumentationSource("regras", "Regras", DocumentationKind.FILE, "/docs/regras.md")

        assertEquals(DocumentAuthority.REFERENCE, source.authority)
    }

    @Test
    fun `formato de texto e lido como esta, e formato binario e extraido`() {
        listOf("regras.md", "notas.txt", "config.yaml", "dados.json", "tabela.csv").forEach {
            assertTrue(SupportedDocumentFormats.isReadableAsText(Path.of(it)), it)
            assertTrue(SupportedDocumentFormats.isSupported(Path.of(it)), it)
            assertTrue(!SupportedDocumentFormats.isExtractable(Path.of(it)), "texto puro não se extrai: $it")
        }

        listOf("manual.pdf", "regras.docx", "casos.xlsx", "aula.pptx").forEach {
            assertTrue(SupportedDocumentFormats.isSupported(Path.of(it)), it)
            assertTrue(SupportedDocumentFormats.isExtractable(Path.of(it)), it)
        }
    }

    @Test
    fun `formato desconhecido nao entra`() {
        listOf("binario.exe", "planilha.ods", "imagem.png", "documento.doc", "sem-extensao").forEach {
            assertTrue(!SupportedDocumentFormats.isSupported(Path.of(it)), it)
        }
    }
}
