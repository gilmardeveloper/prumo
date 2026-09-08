package io.prumo.mcp.toolsets

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * O desfecho que a trilha grava quando o Prumo recusa um caminho excluído.
 *
 * Recusa deliberada e falha do produto não podem chegar iguais a quem lê a trilha depois: uma diz
 * que o alcance funcionou, a outra que algo quebrou.
 */
@Tag("security")
class PathExclusionRefusalTest {

    @Test
    fun `recusa por exclusao entra na trilha como recusa`() {
        val ramo = fonte("toolsets/PrumoToolCall.kt")
            .substringAfter("catch (failure: PathExcludedException) {")
            .substringBefore("} catch")

        assertTrue(
            ramo.contains("AuditResult.DENIED"),
            "exclusão gravada com desfecho diferente de recusa: $ramo",
        )
    }

    @Test
    fun `a excecao de exclusao nao carrega o caminho recusado`() {
        val declaracao = fonte("repository/PathSecurityValidator.kt")
            .substringAfter("class PathExcludedException")
            .substringBefore(")")

        assertTrue(
            !declaracao.contains("relativePath") && !declaracao.contains("path"),
            "a exceção guarda o caminho, e quem a montar por descuido o devolve ao cliente",
        )
    }

    /**
     * A recusa por exclusão acontece em quatro pontos, e todos precisam lançar o tipo que a trilha
     * grava como recusa. Um deles com o tipo antigo devolve a mesma mensagem ao cliente e grava
     * `ERROR` — a defesa some da trilha sem que nada acuse.
     */
    @Test
    fun `nenhuma recusa por exclusao usa o tipo que a trilha grava como falha`() {
        val arquivos = listOf(
            "repository/RepositoryReader.kt",
            "toolsets/RepositoryToolset.kt",
            "toolsets/KnowledgeToolset.kt",
        )

        arquivos.forEach { arquivo ->
            val fonte = fonte(arquivo)
            var de = fonte.indexOf(FRASE)
            while (de >= 0) {
                val antes = fonte.substring(maxOf(0, de - LARGURA), de)
                assertTrue(
                    antes.substringAfterLast("throw ").startsWith("PathExcludedException"),
                    "recusa por exclusão em $arquivo lançando outro tipo: ...$antes",
                )
                de = fonte.indexOf(FRASE, de + 1)
            }
        }
    }

    private fun fonte(caminho: String): String =
        Files.readString(Path.of("src/main/kotlin/io/prumo/mcp/$caminho"))

    private companion object {
        /** O começo da frase única com que o produto recusa um caminho excluído. */
        const val FRASE = "is excluded from"

        /** Quanto do código antes da frase basta para achar o `throw` que a lança. */
        const val LARGURA = 200
    }
}
