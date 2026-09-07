package io.prumo.mcp.documentation

import io.prumo.mcp.repository.PathAccessDeniedException
import io.prumo.mcp.repository.PathRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A leitura de documentação resolve caminho pelo mesmo validador das demais superfícies.
 *
 * Uma segunda implementação da mesma regra diverge quando só uma das duas é endurecida, e a recusa
 * precisa chegar ao cliente classificada como recusa de caminho.
 */
@Tag("security")
class DocumentationPathTest {

    @Test
    fun `caminho absoluto de windows e recusado com a categoria certa`(@TempDir root: Path) {
        val pasta = pastaComManual(root)

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(fonte(pasta), "C:/Windows/win.ini")
        }

        assertEquals(PathRejection.ABSOLUTE_PATH, recusa.rejection)
    }

    @Test
    fun `caminho absoluto de unix e recusado`(@TempDir root: Path) {
        val pasta = pastaComManual(root)

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(fonte(pasta), "/etc/passwd")
        }

        assertEquals(PathRejection.ABSOLUTE_PATH, recusa.rejection)
    }

    @Test
    fun `caminho de home e recusado`(@TempDir root: Path) {
        val pasta = pastaComManual(root)

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(fonte(pasta), "~/.ssh/id_rsa")
        }

        assertEquals(PathRejection.ABSOLUTE_PATH, recusa.rejection)
    }

    @Test
    fun `travessia e recusada`(@TempDir root: Path) {
        val pasta = pastaComManual(root)
        arquivo(root, "segredo.md", "conteudo fora da fonte")

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(fonte(pasta), "../segredo.md")
        }

        assertEquals(PathRejection.PARENT_TRAVERSAL, recusa.rejection)
        assertTrue(
            !recusa.message.orEmpty().contains("conteudo fora"),
            "a recusa não pode ecoar o conteúdo do arquivo",
        )
    }

    @Test
    fun `travessia disfarcada por barra invertida e recusada`(@TempDir root: Path) {
        val pasta = pastaComManual(root)
        arquivo(root, "segredo.md", "conteudo fora da fonte")

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(fonte(pasta), "..\\segredo.md")
        }

        assertEquals(PathRejection.PARENT_TRAVERSAL, recusa.rejection)
    }

    /**
     * Caractere de controle no meio do nome. A implementação anterior da documentação não o
     * recusava.
     */
    @Test
    fun `caractere de controle no caminho e recusado`(@TempDir root: Path) {
        val pasta = pastaComManual(root)

        val recusa = assertThrows(PathAccessDeniedException::class.java) {
            DocumentationReader.read(fonte(pasta), "manu\u0000al.md")
        }

        assertEquals(PathRejection.INVALID_SYNTAX, recusa.rejection)
    }

    @Test
    fun `o arquivo legitimo continua sendo lido`(@TempDir root: Path) {
        val pasta = pastaComManual(root)

        val slice = DocumentationReader.read(fonte(pasta), "manual.md")

        assertEquals("manual.md", slice.path)
        assertTrue(slice.text.contains("linha 1"), slice.text)
    }

    @Test
    fun `arquivo em subpasta da fonte continua sendo lido`(@TempDir root: Path) {
        val pasta = pastaComManual(root)
        arquivo(pasta, "guia/instalacao.md")

        val slice = DocumentationReader.read(fonte(pasta), "guia/instalacao.md")

        assertTrue(slice.text.contains("linha 1"), slice.text)
    }

    private fun pastaComManual(root: Path): Path =
        root.resolve("docs").also { it.createDirectories() }.also { arquivo(it, "manual.md") }

    private fun arquivo(root: Path, name: String, content: String = "linha 1\nlinha 2\nlinha 3"): Path =
        root.resolve(name).also { it.parent?.createDirectories(); it.writeText(content) }

    private fun fonte(location: Path) = DocumentationSource(
        id = "docs",
        name = location.fileName.toString(),
        kind = DocumentationKind.DIRECTORY,
        location = location.toString(),
        authority = DocumentAuthority.OFFICIAL,
    )
}
