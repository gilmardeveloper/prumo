package io.prumo.mcp.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Tag("security")
class PathSecurityValidatorTest {

    private fun repository(root: Path): Path {
        val repository = root.resolve("repo")
        Files.createDirectories(repository.resolve("src/main/java"))
        Files.writeString(repository.resolve("src/main/java/App.java"), "class App {}")
        Files.writeString(repository.resolve("README.md"), "# repo")
        return repository
    }

    @Test
    fun `resolve caminho relativo legitimo`(@TempDir root: Path) {
        val repository = repository(root)

        val resolved = PathSecurityValidator.resolve(repository, "src/main/java/App.java")

        assertEquals(repository.resolve("src/main/java/App.java").normalize(), resolved)
    }

    @Test
    fun `aceita nome de arquivo com espaco`(@TempDir root: Path) {
        val repository = repository(root)
        Files.writeString(repository.resolve("notas de release.md"), "ok")

        val resolved = PathSecurityValidator.resolve(repository, "notas de release.md")

        assertTrue(Files.exists(resolved))
    }

    @Test
    fun `recusa travessia com ponto ponto`(@TempDir root: Path) {
        val repository = repository(root)
        Files.writeString(root.resolve("segredo.txt"), "conteudo fora do repositorio")

        listOf(
            "../segredo.txt",
            "src/../../segredo.txt",
            "src/main/../../../segredo.txt",
            "..",
            "src/..",
            "..\\segredo.txt",
        ).forEach { attempt ->
            val failure = assertThrows(PathAccessDeniedException::class.java, {
                PathSecurityValidator.resolve(repository, attempt)
            }, attempt)
            assertEquals(PathRejection.PARENT_TRAVERSAL, failure.rejection, attempt)
        }
    }

    @Test
    fun `recusa caminho absoluto em qualquer plataforma`(@TempDir root: Path) {
        val repository = repository(root)

        listOf(
            "/etc/passwd",
            "/home/dev/outro-projeto/src/App.java",
            "C:\\Windows\\System32\\config",
            "D:/repos/outro/App.java",
            "\\\\servidor\\compartilhado\\arquivo",
            "~/.ssh/id_rsa",
        ).forEach { attempt ->
            val failure = assertThrows(PathAccessDeniedException::class.java, {
                PathSecurityValidator.resolve(repository, attempt)
            }, attempt)
            assertEquals(PathRejection.ABSOLUTE_PATH, failure.rejection, attempt)
        }
    }

    @Test
    fun `recusa caminho vazio e caractere de controle`(@TempDir root: Path) {
        val repository = repository(root)

        assertEquals(
            PathRejection.EMPTY_PATH,
            assertThrows(PathAccessDeniedException::class.java) {
                PathSecurityValidator.resolve(repository, "   ")
            }.rejection,
        )
        assertEquals(
            PathRejection.INVALID_SYNTAX,
            assertThrows(PathAccessDeniedException::class.java) {
                PathSecurityValidator.resolve(repository, "src/App\u0000.java")
            }.rejection,
        )
    }

    @Test
    fun `link simbolico nao serve de fuga da raiz`(@TempDir root: Path) {
        val repository = repository(root)
        val outside = root.resolve("fora")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("segredo.txt"), "conteudo fora do repositorio")

        val link = repository.resolve("atalho")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: IOException) {
            assumeTrue(false, "criacao de link simbolico indisponivel nesta maquina")
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "sistema de arquivos sem suporte a link simbolico")
        }

        val failure = assertThrows(PathAccessDeniedException::class.java) {
            PathSecurityValidator.resolve(repository, "atalho/segredo.txt")
        }
        assertEquals(PathRejection.ROOT_ESCAPE, failure.rejection)
    }

    @Test
    fun `caminho para arquivo ainda inexistente e permitido dentro da raiz`(@TempDir root: Path) {
        val repository = repository(root)

        val resolved = PathSecurityValidator.resolve(repository, "src/main/java/Novo.java")

        assertTrue(resolved.startsWith(repository.toAbsolutePath().normalize()))
    }
}
