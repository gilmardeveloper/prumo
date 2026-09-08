package io.prumo.mcp.workspace

import io.prumo.mcp.repository.RepositoryReader
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * O caminho digitado na tela precisa chegar ao casamento na forma que ele entende.
 *
 * A tela diz que o caminho é lido a partir da raiz do repositório, e quem escreve assim escreve com
 * a barra na frente. Recusar a barra fazia o vínculo inteiro ser descartado sem aviso.
 */
@Tag("security")
class ExcludedPathNormalizationTest {

    private fun binding(vararg excluded: String) = RepositoryBinding(
        id = "folha",
        name = "folha",
        localPath = "C:/repos/folha",
        role = RepositoryRole.REFERENCE,
        accessMode = AccessMode.READ_ONLY,
        excludedPaths = excluded.toList(),
    )

    @Test
    fun `a barra inicial e descartada`() {
        assertEquals("BANCO_DE_DADOS", RepositoryBinding.normalizeExcludedPath("/BANCO_DE_DADOS"))
        assertEquals("FONTES/curl", RepositoryBinding.normalizeExcludedPath("/FONTES/curl"))
        assertEquals("FONTES/.settings", RepositoryBinding.normalizeExcludedPath("/FONTES/.settings"))
    }

    @Test
    fun `barra invertida do Windows vira barra`() {
        assertEquals("FONTES/config", RepositoryBinding.normalizeExcludedPath("""\FONTES\config"""))
    }

    @Test
    fun `barra final e espaco em volta nao mudam o caminho`() {
        assertEquals("target", RepositoryBinding.normalizeExcludedPath("  target/  "))
    }

    @Test
    fun `o que nao tem leitura relativa e recusado`() {
        assertNull(RepositoryBinding.normalizeExcludedPath("../fora"))
        assertNull(RepositoryBinding.normalizeExcludedPath("C:/Windows"))
        assertNull(RepositoryBinding.normalizeExcludedPath("/"))
        assertNull(RepositoryBinding.normalizeExcludedPath("   "))
    }

    /** A forma que a tela produz é sempre uma forma que o domínio aceita. */
    @Test
    fun `o vinculo aceita tudo o que a normalizacao devolve`() {
        listOf("/BANCO_DE_DADOS", """\FONTES\curl""", "/FONTES/.settings", "FONTES/config/", "target")
            .mapNotNull(RepositoryBinding::normalizeExcludedPath)
            .forEach { canonico -> binding(canonico) }
    }

    @Test
    fun `o vinculo continua recusando a forma nao canonica`() {
        assertThrows(IllegalArgumentException::class.java) { binding("/BANCO_DE_DADOS") }
    }

    /** O que se exclui digitando com barra tem de ficar de fora de verdade. */
    @Test
    fun `o caminho normalizado e recusado pela leitura`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("FONTES/curl"))
        Files.writeString(root.resolve("FONTES/curl/chamada.sh"), "curl https://exemplo")
        Files.writeString(root.resolve("leiame.txt"), "conteudo livre")
        val excluded = listOfNotNull(RepositoryBinding.normalizeExcludedPath("/FONTES/curl"))

        val recusa = assertThrows(io.prumo.mcp.repository.PathExcludedException::class.java) {
            RepositoryReader.readFile(root, "FONTES/curl/chamada.sh", excluded = excluded)
        }

        assertTrue(recusa.message.orEmpty().contains("excluded"), recusa.message.orEmpty())
        assertTrue(RepositoryReader.readFile(root, "leiame.txt", excluded = excluded).text.isNotBlank())
    }
}
