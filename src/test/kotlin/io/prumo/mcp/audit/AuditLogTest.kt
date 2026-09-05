package io.prumo.mcp.audit

import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AuditLogTest {

    private fun log(root: Path) = AuditLog(
        FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        ),
        clock = { Instant.parse("2026-09-04T12:00:00Z") },
    )

    @Test
    fun `registra a operacao com os campos esperados`(@TempDir root: Path) {
        val log = log(root)

        log.record(
            workspaceId = "folha",
            tool = "database.execute_readonly",
            operation = "SELECT",
            result = AuditResult.SUCCESS,
            durationMillis = 42,
            datasourceId = "legacy-hom",
            details = mapOf("statementType" to "SELECT", "rowCount" to "12"),
        )

        val entries = log.read("folha")
        assertEquals(1, entries.size)
        assertEquals("database.execute_readonly", entries.single().tool)
        assertEquals("12", entries.single().details["rowCount"])
    }

    @Test
    fun `a auditoria de um workspace nao aparece em outro`(@TempDir root: Path) {
        val log = log(root)
        log.record("alpha", "workspace.get_context", "read", AuditResult.SUCCESS, 1)
        log.record("beta", "workspace.get_context", "read", AuditResult.SUCCESS, 1)

        assertEquals(1, log.read("alpha").size)
        assertEquals("beta", log.read("beta").single().workspaceId)
    }

    @Test
    fun `segredo nunca chega ao arquivo de auditoria`(@TempDir root: Path) {
        val log = log(root)

        log.record(
            workspaceId = "folha",
            tool = "database.test_connection",
            operation = "connect",
            result = AuditResult.ERROR,
            durationMillis = 10,
            details = mapOf(
                "password" to "s3nh4-muito-secreta",
                "apiKey" to "sk-abcdef123456",
                "connectionString" to "jdbc:postgresql://host:5432/db?password=s3nh4",
                "authorization" to "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature",
                "environment" to "HOM",
            ),
        )

        val raw = Files.readString(
            root.resolve("data/workspaces/folha/audit/audit.jsonl"),
        )

        listOf("s3nh4-muito-secreta", "sk-abcdef123456", "jdbc:postgresql", "eyJhbGciOiJIUzI1NiJ9").forEach {
            assertTrue(!raw.contains(it), "auditoria vazou: $it")
        }
        assertTrue(raw.contains("HOM"), "campo nao sensivel deve ser preservado")
    }

    @Test
    fun `valor longo demais e truncado para nao virar copia do conteudo`() {
        val sanitized = AuditSanitizer.sanitize(mapOf("preview" to "a".repeat(500)))

        assertTrue(sanitized.getValue("preview").length < 130)
    }

    @Test
    fun `chaves sensiveis sao reconhecidas em qualquer grafia`() {
        val sanitized = AuditSanitizer.sanitize(
            mapOf(
                "API_KEY" to "x",
                "user-password" to "y",
                "AuthToken" to "z",
                "senha" to "w",
                "rowCount" to "7",
            ),
        )

        assertEquals(setOf("[redacted]"), sanitized.filterKeys { it != "rowCount" }.values.toSet())
        assertEquals("7", sanitized["rowCount"])
    }
}
