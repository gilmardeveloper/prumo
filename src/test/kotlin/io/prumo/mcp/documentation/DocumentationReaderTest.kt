package io.prumo.mcp.documentation

import io.prumo.mcp.repository.PathAccessDeniedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A leitura de documentação é uma superfície nova de acesso a disco.
 *
 * O alcance é o que o desenvolvedor cadastrou: caminho absoluto, travessia e escape da raiz são
 * recusados, e a recusa nomeia a categoria da violação.
 */
@Tag("security")
class DocumentationReaderTest {

    private fun file(root: Path, name: String, content: String = "linha 1\nlinha 2\nlinha 3"): Path =
        root.resolve(name).also { it.parent?.createDirectories(); it.writeText(content) }

    private fun source(id: String, location: Path, kind: DocumentationKind) = DocumentationSource(
        id = id,
        name = location.fileName.toString(),
        kind = kind,
        location = location.toString(),
        authority = DocumentAuthority.OFFICIAL,
    )

    @Test
    fun `le um markdown cadastrado como arquivo`(@TempDir root: Path) {
        val manual = file(root, "manual.md")

        val slice = DocumentationReader.read(source("manual", manual, DocumentationKind.FILE))

        assertEquals("linha 1\nlinha 2\nlinha 3", slice.text)
        assertEquals(3, slice.totalLines)
        assertFalse(slice.truncated)
    }

    @Test
    fun `pagina por linha e sinaliza que ha continuacao`(@TempDir root: Path) {
        val manual = file(root, "manual.md", (1..10).joinToString("\n") { "linha $it" })

        val slice = DocumentationReader.read(source("manual", manual, DocumentationKind.FILE), maxLines = 4)

        assertEquals(1, slice.firstLine)
        assertEquals(4, slice.lastLine)
        assertEquals(10, slice.totalLines)
        assertTrue(slice.truncated)
    }

    @Test
    fun `le um arquivo dentro de uma pasta cadastrada`(@TempDir root: Path) {
        val pasta = root.resolve("docs").also { it.createDirectories() }
        file(pasta, "eventos/S-1200.md", "# S-1200")

        val slice = DocumentationReader.read(source("docs", pasta, DocumentationKind.DIRECTORY), "eventos/S-1200.md")

        assertEquals("# S-1200", slice.text)
    }

    @Test
    fun `formato apenas catalogado e recusado com explicacao`(@TempDir root: Path) {
        val pdf = file(root, "leiaute.pdf", "%PDF-1.4")

        val recusa = assertThrows(DocumentationReadException::class.java) {
            DocumentationReader.read(source("leiaute", pdf, DocumentationKind.FILE))
        }

        assertTrue(recusa.message.orEmpty().contains("does not extract text"), recusa.message.orEmpty())
    }

    @Test
    fun `caminho absoluto e recusado`(@TempDir root: Path) {
        val pasta = root.resolve("docs").also { it.createDirectories() }
        file(pasta, "manual.md")

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(source("docs", pasta, DocumentationKind.DIRECTORY), "C:/Windows/win.ini")
        }

        assertTrue(recusa.message.orEmpty().contains("absolute path"), recusa.message.orEmpty())
    }

    @Test
    fun `travessia para fora da fonte e recusada`(@TempDir root: Path) {
        val pasta = root.resolve("docs").also { it.createDirectories() }
        file(pasta, "manual.md")
        file(root, "segredo.md", "conteudo fora da fonte")

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(source("docs", pasta, DocumentationKind.DIRECTORY), "../segredo.md")
        }

        assertTrue(recusa.message.orEmpty().contains("parent traversal"), recusa.message.orEmpty())
        assertFalse(recusa.message.orEmpty().contains("conteudo fora"), "a recusa não pode ecoar o conteúdo")
    }

    @Test
    fun `fonte que sumiu do disco e recusada`(@TempDir root: Path) {
        val ausente = root.resolve("nao-existe.md")

        val recusa = assertThrows(DocumentationReadException::class.java) {
            DocumentationReader.read(source("ausente", ausente, DocumentationKind.FILE))
        }

        assertTrue(recusa.message.orEmpty().contains("no longer where"), recusa.message.orEmpty())
    }

    @Test
    fun `arquivo inexistente dentro da fonte e recusado sem revelar o disco`(@TempDir root: Path) {
        val pasta = root.resolve("docs").also { it.createDirectories() }
        file(pasta, "manual.md")

        val recusa = assertThrows(DocumentationReadException::class.java) {
            DocumentationReader.read(source("docs", pasta, DocumentationKind.DIRECTORY), "ausente.md")
        }

        assertTrue(recusa.message.orEmpty().contains("does not exist"), recusa.message.orEmpty())
        assertFalse(recusa.message.orEmpty().contains(root.toString()), "a recusa não pode revelar o caminho de disco")
    }

    @Test
    fun `pasta sem caminho interno pede o arquivo`(@TempDir root: Path) {
        val pasta = root.resolve("docs").also { it.createDirectories() }
        file(pasta, "manual.md")

        val recusa = assertThrows(DocumentationReadException::class.java) {
            DocumentationReader.read(source("docs", pasta, DocumentationKind.DIRECTORY))
        }

        assertTrue(recusa.message.orEmpty().contains("is a folder"), recusa.message.orEmpty())
    }

    @Test
    fun `enumera os arquivos legiveis de uma pasta`(@TempDir root: Path) {
        val pasta = root.resolve("docs").also { it.createDirectories() }
        file(pasta, "a.md")
        file(pasta, "sub/b.md")
        file(pasta, "imagem.png", "binario")

        val arquivos = DocumentationReader.list(source("docs", pasta, DocumentationKind.DIRECTORY))

        assertEquals(listOf("a.md", "sub/b.md"), arquivos)
    }
}
