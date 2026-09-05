package io.prumo.mcp.pack

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.pack.application.PackAccessException
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.LocalizedText
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.PackTool
import io.prumo.mcp.pack.domain.PackToolKind
import io.prumo.mcp.pack.execution.PackToolRunner
import io.prumo.mcp.platform.OperatingSystemFamily
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.policy.Capability
import io.prumo.mcp.policy.PolicyViolationException
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.storage.FileSystemStorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackToolRunnerTest {

    private val windows = OperatingSystemFamily.fromName(System.getProperty("os.name")) == OperatingSystemFamily.WINDOWS
    private val executionAllowed = WorkspacePolicies(processExecution = true)

    private fun storage(root: Path) = FileSystemStorageProvider(
        PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
    )

    private fun tool(command: String, timeoutSeconds: Int = 20) = PackTool(
        id = "relatorio",
        title = LocalizedText("Report"),
        description = LocalizedText("Prints a report"),
        kind = PackToolKind.SCRIPT,
        capabilities = setOf(Capability.PROCESS_EXECUTE),
        commands = mapOf(
            "windows" to listOf("cmd.exe", "/c", command),
            "linux" to listOf("/bin/sh", "-c", command),
            "macos" to listOf("/bin/sh", "-c", command),
        ),
        timeoutSeconds = timeoutSeconds,
    )

    private fun install(root: Path, capabilities: Set<Capability>, tool: PackTool): PackStore {
        val store = PackStore(storage(root))
        store.save(
            "folha-2026",
            PackManifest(
                id = "folha-tools",
                version = "1.0.0",
                title = LocalizedText("Payroll tools"),
                description = LocalizedText("Scripts of the payroll team"),
                capabilities = capabilities,
                tools = listOf(tool),
                createdAt = "2026-09-05T00:00:00Z",
                updatedAt = "2026-09-05T00:00:00Z",
            ),
        )
        return store
    }

    @Test
    fun `script roda e devolve a saida`(@TempDir root: Path) {
        install(root, setOf(Capability.PROCESS_EXECUTE), tool("echo relatorio-pronto"))
        val runner = PackToolRunner(storage(root))

        val result = runner.run("folha-2026", executionAllowed, "folha-tools", "relatorio")

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("relatorio-pronto"))
        assertFalse(result.timedOut)
    }

    @Test
    fun `o script trabalha dentro do proprio pack, nunca no repositorio do usuario`(@TempDir root: Path) {
        install(root, setOf(Capability.PROCESS_EXECUTE), tool(if (windows) "cd" else "pwd"))
        val runner = PackToolRunner(storage(root))

        val result = runner.run("folha-2026", executionAllowed, "folha-tools", "relatorio")

        val esperado = storage(root).workspaceRoot("folha-2026").resolve("packs/folha-tools/work")
        assertTrue(
            result.stdout.trim().lowercase().contains(esperado.toRealPath().toString().lowercase()),
            result.stdout,
        )
    }

    @Test
    fun `politica do workspace que nega execucao barra o script`(@TempDir root: Path) {
        install(root, setOf(Capability.PROCESS_EXECUTE), tool("echo nao-deveria-rodar"))
        val runner = PackToolRunner(storage(root))

        assertThrows<PolicyViolationException> {
            runner.run("folha-2026", WorkspacePolicies.DENY_ALL, "folha-tools", "relatorio")
        }
    }

    @Test
    fun `pack sem a capacidade declarada nao executa, mesmo com a politica liberada`(@TempDir root: Path) {
        install(root, capabilities = emptySet(), tool = tool("echo nao-deveria-rodar"))
        val runner = PackToolRunner(storage(root))

        val failure = assertThrows<Exception> {
            runner.run("folha-2026", executionAllowed, "folha-tools", "relatorio")
        }

        assertTrue(
            failure is PolicyViolationException || failure is PackAccessException,
            failure::class.simpleName.orEmpty(),
        )
    }

    @Test
    fun `pack de outro workspace nao e executavel`(@TempDir root: Path) {
        install(root, setOf(Capability.PROCESS_EXECUTE), tool("echo x"))
        val runner = PackToolRunner(storage(root))

        assertThrows<PackAccessException> {
            runner.run("outro-workspace", executionAllowed, "folha-tools", "relatorio")
        }
    }

    @Test
    fun `a auditoria registra a invocacao com o pack de origem e sem a saida`(@TempDir root: Path) {
        install(root, setOf(Capability.PROCESS_EXECUTE), tool("echo conteudo-sensivel-do-relatorio"))
        val log = AuditLog(storage(root))

        PackToolRunner(storage(root)).run("folha-2026", executionAllowed, "folha-tools", "relatorio", log)

        val entry = log.read("folha-2026").single()
        val raw = Files.readString(
            storage(root).workspaceRoot("folha-2026").resolve("audit").resolve("audit.jsonl"),
        )
        assertEquals("folha-tools", entry.packId)
        assertEquals("pack.run_tool", entry.operation)
        assertEquals("0", entry.details["exitCode"])
        assertFalse(raw.contains("conteudo-sensivel-do-relatorio"), "a saida do script nao entra na trilha")
    }

    @Test
    fun `script que passa do tempo e cortado e a auditoria registra`(@TempDir root: Path) {
        install(
            root,
            setOf(Capability.PROCESS_EXECUTE),
            tool(if (windows) "ping -n 20 127.0.0.1 > nul" else "sleep 20", timeoutSeconds = 2),
        )
        val log = AuditLog(storage(root))

        val result = PackToolRunner(storage(root)).run("folha-2026", executionAllowed, "folha-tools", "relatorio", log)

        assertTrue(result.timedOut)
        assertEquals("true", log.read("folha-2026").single().details["timedOut"])
    }
}
