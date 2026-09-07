package io.prumo.mcp.toolsets

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.readText

/**
 * O desfecho de uma chamada interrompida pelo cliente.
 *
 * Cancelamento não é falha do produto: a trilha precisa dizer que a chamada foi interrompida, e a
 * exceção precisa continuar subindo para que a cadeia de cancelamento do chamador não se perca.
 */
class ToolCallCancellationTest {

    @Test
    fun `cancelamento tem desfecho proprio na trilha`(@TempDir root: Path) {
        val log = log(root)

        log.record(
            workspaceId = "folha",
            tool = "database.execute_readonly",
            operation = "SELECT",
            result = AuditResult.CANCELLED,
            durationMillis = 8,
        )

        assertEquals(AuditResult.CANCELLED, log.read("folha").single().result)
    }

    /**
     * A trilha é append-only e sobrevive à versão que a escreveu: valor acrescentado ao enum não
     * pode impedir a leitura do que já está gravado.
     */
    @Test
    fun `entrada gravada antes do desfecho novo continua legivel`(@TempDir root: Path) {
        val log = log(root)
        log.record(
            workspaceId = "folha",
            tool = "repository.read_file",
            operation = "READ",
            result = AuditResult.ERROR,
            durationMillis = 3,
        )
        log.record(
            workspaceId = "folha",
            tool = "repository.read_file",
            operation = "READ",
            result = AuditResult.CANCELLED,
            durationMillis = 4,
        )

        val entries = log.read("folha")

        assertEquals(listOf(AuditResult.ERROR, AuditResult.CANCELLED), entries.map { it.result })
        assertEquals(2, log.readLast("folha", 10).size)
    }

    /**
     * A razão de o tratamento existir: sem um `catch` próprio, o cancelamento cai no genérico e é
     * gravado como falha.
     */
    @Test
    fun `cancelamento e capturado por um catch de Exception`() {
        assertTrue(
            Exception::class.java.isAssignableFrom(CancellationException::class.java),
            "CancellationException é Exception: sem catch próprio, o ramo genérico a engole",
        )
    }

    @Test
    fun `o catch de cancelamento vem antes do catch generico`() {
        val source = Path.of("src/main/kotlin/io/prumo/mcp/toolsets/PrumoToolCall.kt").readText()

        val cancelamento = source.indexOf("catch (cancellation: CancellationException)")
        val generico = source.indexOf("catch (failure: Exception)")

        assertTrue(cancelamento >= 0, "não há tratamento próprio de cancelamento")
        assertTrue(generico >= 0, "o ramo genérico sumiu")
        assertTrue(
            cancelamento < generico,
            "o catch genérico vem antes e captura o cancelamento primeiro",
        )
    }

    @Test
    fun `o cancelamento e relancado, nunca engolido`() {
        val source = Path.of("src/main/kotlin/io/prumo/mcp/toolsets/PrumoToolCall.kt").readText()
        val ramo = source.substringAfter("catch (cancellation: CancellationException)")
            .substringBefore("catch (failure: Exception)")

        assertTrue(ramo.contains("throw cancellation"), "engolir cancelamento quebra a cadeia do chamador")
        assertTrue(
            !ramo.contains("LOG.warn"),
            "cancelamento não é falha: não vai para o log com rastro de pilha",
        )
    }

    private fun log(root: Path) = AuditLog(
        FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        ),
        clock = { Instant.parse("2026-09-07T12:00:00Z") },
    )
}
