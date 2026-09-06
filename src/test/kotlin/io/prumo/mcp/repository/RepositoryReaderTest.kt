package io.prumo.mcp.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@Tag("security")
class RepositoryReaderTest {

    private fun repository(root: Path): Path {
        root.resolve("src/main/kotlin").createDirectories()
        root.resolve("src/main/kotlin/Prumo.kt").writeText(
            (1..10).joinToString("\n") { "linha $it" },
        )
        root.resolve("docs").createDirectories()
        root.resolve("docs/manual.md").writeText("# Manual\nconteudo do manual\n")
        root.resolve(".git").createDirectories()
        root.resolve(".git/config").writeText("[core]\n    segredo = nao deve aparecer\n")
        return root
    }

    @Test
    fun `le um trecho do arquivo e diz que cortou`(@TempDir root: Path) {
        val slice = RepositoryReader.readFile(repository(root), "src/main/kotlin/Prumo.kt", firstLine = 2, maxLines = 3)

        assertEquals("linha 2\nlinha 3\nlinha 4", slice.text)
        assertEquals(2, slice.firstLine)
        assertEquals(4, slice.lastLine)
        assertEquals(10, slice.totalLines)
        assertTrue(slice.truncated)
    }

    @Test
    fun `caminho absoluto do cliente e recusado`(@TempDir root: Path) {
        val repository = repository(root)
        val absolute = repository.resolve("docs/manual.md").toString()

        val failure = assertThrows<PathAccessDeniedException> {
            RepositoryReader.readFile(repository, absolute)
        }

        assertEquals(PathRejection.ABSOLUTE_PATH, failure.rejection)
    }

    @Test
    fun `leitura fora da raiz do repositorio e recusada`(@TempDir root: Path) {
        val repository = repository(root)
        root.resolve("vizinho.txt").writeText("conteudo do vizinho")

        val failure = assertThrows<PathAccessDeniedException> {
            RepositoryReader.readFile(repository.resolve("docs"), "../../vizinho.txt")
        }

        assertEquals(PathRejection.PARENT_TRAVERSAL, failure.rejection)
    }

    @Test
    fun `arquivo binario nao e entregue como texto`(@TempDir root: Path) {
        val repository = repository(root)
        Files.write(repository.resolve("docs/imagem.png"), byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x0D))

        val failure = assertThrows<RepositoryReadException> {
            RepositoryReader.readFile(repository, "docs/imagem.png")
        }

        assertTrue(failure.message.orEmpty().contains("binary"))
    }

    @Test
    fun `arquivo inexistente falha com mensagem acionavel`(@TempDir root: Path) {
        val failure = assertThrows<RepositoryReadException> {
            RepositoryReader.readFile(repository(root), "docs/ausente.md")
        }

        assertTrue(failure.message.orEmpty().contains("does not exist"))
    }

    @Test
    fun `a busca devolve caminho relativo e nunca entra no diretorio do Git`(@TempDir root: Path) {
        val outcome = RepositoryReader.searchText(repository(root), "segredo")

        assertTrue(outcome.matches.isEmpty(), "o conteudo de .git nao pode ser lido")
    }

    @Test
    fun `a busca acha o texto e corta no limite pedido`(@TempDir root: Path) {
        val repository = repository(root)

        val outcome = RepositoryReader.searchText(repository, "linha", maxResults = 4)

        assertEquals(4, outcome.matches.size)
        assertTrue(outcome.truncated)
        assertEquals("src/main/kotlin/Prumo.kt", outcome.matches.first().path)
        assertEquals(1, outcome.matches.first().line)
    }

    @Test
    fun `a busca restrita a um subdiretorio nao alcanca o resto`(@TempDir root: Path) {
        val outcome = RepositoryReader.searchText(repository(root), "linha", scope = "docs")

        assertTrue(outcome.matches.isEmpty())
    }

    @Test
    fun `a estrutura lista caminhos relativos sem o diretorio do Git`(@TempDir root: Path) {
        val listing = RepositoryReader.listDirectory(repository(root), maxDepth = 3)

        val paths = listing.entries.map { it.path }
        assertTrue(paths.contains("docs/manual.md"))
        assertTrue(paths.none { it.startsWith(".git") }, "o diretorio do Git nao aparece na estrutura")
        assertTrue(paths.none { it.contains(root.toString()) }, "nenhum caminho absoluto na listagem")
    }

    @Test
    fun `a estrutura corta no limite de entradas`(@TempDir root: Path) {
        val listing = RepositoryReader.listDirectory(repository(root), maxDepth = 5, maxEntries = 2)

        assertEquals(2, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `estrutura de caminho que nao e diretorio falha explicitamente`(@TempDir root: Path) {
        val failure = assertThrows<RepositoryReadException> {
            RepositoryReader.listDirectory(repository(root), "docs/manual.md")
        }

        assertTrue(failure.message.orEmpty().contains("not a directory"))
        assertFalse(failure.message.orEmpty().contains(root.toString()))
    }

    /**
     * A garantia escrita diz que o `.git` nunca e lido como conteudo. A busca e a listagem ja
     * filtravam; a leitura de arquivo nao, e e ali que mora a URL do remote com token.
     */
    @Test
    fun `leitura de arquivo recusa o diretorio git`(@TempDir root: Path) {
        val git = Files.createDirectories(root.resolve(".git"))
        Files.writeString(git.resolve("config"), "[remote origin] url = https://user:token@host/org/app.git")

        val falha = assertThrows<RepositoryReadException> {
            RepositoryReader.readFile(root, ".git/config")
        }

        assertTrue(falha.message.orEmpty().contains(".git"), falha.message.orEmpty())
        assertFalse(falha.message.orEmpty().contains("token"), "a recusa nao pode ecoar o conteudo")
    }


    /**
     * Exclusao escrita na descricao do repositorio e pedido: um agente cego respeitou, outro listou
     * a pasta assim mesmo. Aqui ela e regra, e vale nos tres pontos de leitura.
     */
    @Test
    fun `caminho excluido e recusado na leitura`(@TempDir root: Path) {
        val repo = repository(root)
        root.resolve("target").createDirectories()
        root.resolve("target/saida.txt").writeText("bytecode")

        val falha = assertThrows<RepositoryReadException> {
            RepositoryReader.readFile(repo, "target/saida.txt", excluded = listOf("target"))
        }

        assertTrue(falha.message.orEmpty().contains("excluded"), falha.message.orEmpty())
        assertFalse(falha.message.orEmpty().contains("bytecode"), "a recusa nao ecoa o conteudo")
    }

    @Test
    fun `caminho excluido nao aparece na listagem nem na busca`(@TempDir root: Path) {
        val repo = repository(root)
        root.resolve(".claude/skills").createDirectories()
        root.resolve(".claude/skills/SKILL.md").writeText("segredo de contexto")

        val listagem = RepositoryReader.listDirectory(repo, null, 3, 300, listOf(".claude"))
        assertFalse(listagem.entries.any { it.path.contains(".claude") }, listagem.entries.toString())

        val busca = RepositoryReader.searchText(repo, "segredo de contexto", excluded = listOf(".claude"))
        assertTrue(busca.matches.isEmpty(), busca.matches.toString())
    }

    @Test
    fun `arquivo solto tambem pode ser excluido`(@TempDir root: Path) {
        val repo = repository(root)
        root.resolve("CLAUDE.md").writeText("instrucoes de IA")

        assertThrows<RepositoryReadException> {
            RepositoryReader.readFile(repo, "CLAUDE.md", excluded = listOf("CLAUDE.md"))
        }
    }

    @Test
    fun `sem exclusao configurada o repositorio continua legivel`(@TempDir root: Path) {
        val repo = repository(root)
        root.resolve("target").createDirectories()
        root.resolve("target/saida.txt").writeText("bytecode")

        val slice = RepositoryReader.readFile(repo, "target/saida.txt")

        assertEquals("bytecode", slice.text)
    }


    /**
     * Um agente cego leu o arquivo excluido inteiro so trocando a caixa do nome: no Windows o
     * sistema de arquivos ignora maiusculas, e a comparacao sobre o texto recebido nao ignorava.
     */
    @Test
    fun `trocar a caixa do nome nao contorna a exclusao`(@TempDir root: Path) {
        val repo = repository(root)
        root.resolve("CLAUDE.md").writeText("instrucoes de IA")
        root.resolve("docs").createDirectories()
        root.resolve("docs/BUILD.md").writeText("como compilar")

        listOf("claude.md", "CLAUDE.MD", "Claude.Md").forEach { grafia ->
            assertThrows<RepositoryReadException>(grafia) {
                RepositoryReader.readFile(repo, grafia, excluded = listOf("CLAUDE.md"))
            }
        }

        listOf("DOCS/BUILD.md", "Docs/build.md").forEach { grafia ->
            assertThrows<RepositoryReadException>(grafia) {
                RepositoryReader.readFile(repo, grafia, excluded = listOf("docs"))
            }
        }
    }

    @Test
    fun `listagem e busca tambem ignoram a caixa da exclusao`(@TempDir root: Path) {
        val repo = repository(root)
        root.resolve("docs").createDirectories()
        root.resolve("docs/BUILD.md").writeText("termo exclusivo do excluido")

        // A raiz e o ponto comum: no Linux "DOCS" nem existe, no Windows existe e e o mesmo diretorio.
        val listagem = RepositoryReader.listDirectory(repo, null, 3, 300, listOf("DOCS"))
        assertTrue(listagem.entries.none { it.path.lowercase().startsWith("docs") }, listagem.entries.toString())

        val busca = RepositoryReader.searchText(repo, "termo exclusivo do excluido", excluded = listOf("DOCS"))
        assertTrue(busca.matches.isEmpty(), busca.matches.toString())
    }

}
