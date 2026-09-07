package io.prumo.mcp.audit

import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Leitura das entradas mais recentes da trilha.
 *
 * A trilha é append-only e cresce sem limite. A tela precisa das últimas, não do arquivo inteiro em
 * memória — e uma linha corrompida no meio não pode esconder as outras.
 */
class AuditLogWindowTest {

    @Test
    fun `workspace sem trilha devolve lista vazia`(@TempDir root: Path) {
        assertTrue(auditFor(root).readLast(WORKSPACE, 10).isEmpty())
    }

    @Test
    fun `devolve as ultimas, da mais antiga para a mais nova`(@TempDir root: Path) {
        val audit = auditFor(root)
        repeat(10) { index -> record(audit, "op-$index") }

        val recent = audit.readLast(WORKSPACE, 3)

        assertEquals(3, recent.size)
        assertEquals(listOf("op-7", "op-8", "op-9"), recent.map { it.operation })
    }

    @Test
    fun `limite maior que a trilha devolve tudo`(@TempDir root: Path) {
        val audit = auditFor(root)
        repeat(2) { index -> record(audit, "op-$index") }

        assertEquals(2, audit.readLast(WORKSPACE, 50).size)
    }

    @Test
    fun `limite zero ou negativo ainda devolve a entrada mais recente`(@TempDir root: Path) {
        val audit = auditFor(root)
        repeat(3) { index -> record(audit, "op-$index") }

        assertEquals(listOf("op-2"), audit.readLast(WORKSPACE, 0).map { it.operation })
        assertEquals(listOf("op-2"), audit.readLast(WORKSPACE, -5).map { it.operation })
    }

    @Test
    fun `linha corrompida no meio nao esconde as demais`(@TempDir root: Path) {
        val audit = auditFor(root)
        record(audit, "antes")
        corrupt(root)
        record(audit, "depois")

        val recent = audit.readLast(WORKSPACE, 10)

        assertEquals(listOf("antes", "depois"), recent.map { it.operation })
    }

    private fun auditFor(root: Path) = AuditLog(
        FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        ),
    )

    private fun record(audit: AuditLog, operation: String) = audit.record(
        workspaceId = WORKSPACE,
        tool = "prumo_test",
        operation = operation,
        result = AuditResult.SUCCESS,
        durationMillis = 1,
    )

    private fun corrupt(root: Path) {
        val file = root.resolve("data").resolve("workspaces").resolve(WORKSPACE).resolve("audit").resolve("audit.jsonl")
        Files.writeString(
            file,
            "{isto nao e json}\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private companion object {
        const val WORKSPACE = "ws-trilha"
    }
}
