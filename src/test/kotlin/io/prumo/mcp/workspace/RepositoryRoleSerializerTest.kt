package io.prumo.mcp.workspace

import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Papel gravado que o enum não tem mais não pode derrubar o workspace.
 *
 * A desserialização de valor de enum desconhecido lança, e a exceção sobe como falha do arquivo
 * inteiro — perderia repositórios, documentação, bancos e políticas de uma vez.
 */
@Tag("security")
class RepositoryRoleSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TARGET foi consolidado em PRIMARY`() {
        val binding = json.decodeFromString<RepositoryBinding>(rawBinding("TARGET"))

        assertEquals(RepositoryRole.PRIMARY, binding.role)
    }

    @Test
    fun `papel desconhecido vira REFERENCE, o mais conservador`() {
        val binding = json.decodeFromString<RepositoryBinding>(rawBinding("PAPEL_QUE_NAO_EXISTE"))

        assertEquals(RepositoryRole.REFERENCE, binding.role)
    }

    @Test
    fun `o acesso gravado nao e afetado pela resolucao do papel`() {
        val binding = json.decodeFromString<RepositoryBinding>(rawBinding("TARGET"))

        assertEquals(AccessMode.READ_ONLY, binding.accessMode)
        assertFalse(binding.writable)
    }

    @Test
    fun `todo papel conhecido sobrevive ao ciclo de gravacao`() {
        RepositoryRole.entries.forEach { role ->
            val original = binding(role)
            val recovered = json.decodeFromString<RepositoryBinding>(json.encodeToString(original))

            assertEquals(role, recovered.role, role.name)
        }
    }

    @Test
    fun `a gravacao escreve o nome do papel, nao um numero`() {
        val serialized = json.encodeToString(binding(RepositoryRole.LEGACY_REFERENCE))

        assertTrue(serialized.contains("\"LEGACY_REFERENCE\""), serialized)
    }

    @Test
    fun `workspace legado com TARGET abre e e regravado com o papel novo`(@TempDir root: Path) {
        val store = WorkspaceStore(
            FileSystemStorageProvider(
                PrumoDirectories(
                    config = root.resolve("config"),
                    data = root.resolve("data"),
                    cache = root.resolve("cache"),
                ),
            ),
        )
        val workspaceRoot = root.resolve("data").resolve("workspaces").resolve("legado")
        Files.createDirectories(workspaceRoot)
        Files.writeString(workspaceRoot.resolve("workspace.json"), LEGACY_WORKSPACE)
        Files.writeString(workspaceRoot.resolve("repositories.json"), "{\"repositories\":[${rawBinding("TARGET")}]}")

        val loaded = store.load("legado")
        assertEquals(RepositoryRole.PRIMARY, loaded?.repositories?.single()?.role)

        store.save(loaded!!)
        val rewritten = Files.readString(workspaceRoot.resolve("repositories.json"))
        assertTrue(rewritten.contains("\"PRIMARY\""), rewritten)
        assertFalse(rewritten.contains("TARGET"), rewritten)
    }

    private fun binding(role: RepositoryRole) = RepositoryBinding(
        id = "legado",
        name = "Legado",
        localPath = "C:/repos/legado",
        role = role,
        accessMode = AccessMode.READ_ONLY,
    )

    private fun rawBinding(role: String) = """
        {
            "id": "legado",
            "name": "Legado",
            "localPath": "C:/repos/legado",
            "role": "$role",
            "accessMode": "READ_ONLY"
        }
    """.trimIndent()

    private companion object {
        val LEGACY_WORKSPACE = """
            {
                "id": "legado",
                "name": "Legado",
                "type": "MODERNIZATION",
                "createdAt": "2026-09-01T00:00:00Z",
                "updatedAt": "2026-09-01T00:00:00Z"
            }
        """.trimIndent()
    }
}
