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

    private fun fonte(caminho: String): String =
        Files.readString(Path.of("src/main/kotlin/io/prumo/mcp/$caminho"))
}
