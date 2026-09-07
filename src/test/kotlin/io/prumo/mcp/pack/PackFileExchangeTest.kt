package io.prumo.mcp.pack

import io.prumo.mcp.pack.application.PackFileExchange
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.KnowledgeItem
import io.prumo.mcp.pack.domain.LocalizedText
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.exchange.PackAcceptance
import io.prumo.mcp.pack.exchange.PackExchangeException
import io.prumo.mcp.pack.exchange.PackImporter
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A troca de packs pelo disco, que é como um pack sai de uma máquina e entra em outra.
 *
 * O que interessa aqui é o que acontece fora do caminho feliz: arquivo que não existe, arquivo que
 * não é pack, e pack que carrega segredo e por isso não pode viajar.
 */
@Tag("security")
class PackFileExchangeTest {

    private fun storage(root: Path) = FileSystemStorageProvider(
        PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
    )

    private fun manifest(id: String = "folha-tools", knowledge: List<KnowledgeItem> = emptyList()) = PackManifest(
        id = id,
        version = "1.0.0",
        title = LocalizedText("Payroll tools"),
        description = LocalizedText("Saved queries of the payroll team"),
        author = "Equipe folha",
        knowledge = knowledge,
        createdAt = "2026-09-06T00:00:00Z",
        updatedAt = "2026-09-06T00:00:00Z",
    )

    private fun acceptance(packId: String, checksum: String) = PackAcceptance(
        acceptedBy = "dev",
        acceptedAt = "2026-09-06T00:00:00Z",
        packId = packId,
        version = "1.0.0",
        checksum = checksum,
        capabilities = emptySet(),
    )

    @Test
    fun `o pack exportado para um arquivo volta inteiro em outra maquina`(@TempDir origem: Path, @TempDir destino: Path, @TempDir pasta: Path) {
        val store = PackStore(storage(origem))
        val item = KnowledgeItem("regras", LocalizedText("Regras"), "knowledge/regras.md", listOf("folha"))
        store.save("origem", manifest(knowledge = listOf(item)))
        store.writeKnowledge("origem", "folha-tools", item, "# Regras\nA rubrica 101 e base.")
        val arquivo = pasta.resolve("folha-tools.json")

        PackFileExchange(store).write("origem", "folha-tools", arquivo)

        val outra = PackStore(storage(destino))
        val preview = PackFileExchange(outra).read(arquivo)
        PackImporter.install(outra, "destino", preview, acceptance("folha-tools", preview.checksum))

        assertTrue(preview.checksumMatches)
        assertEquals("folha-tools", outra.load("destino", "folha-tools")?.id)
        assertTrue(outra.readKnowledge("destino", "folha-tools", "regras").contains("rubrica 101"))
    }

    @Test
    fun `arquivo que nao existe falha dizendo o nome do arquivo`(@TempDir root: Path, @TempDir pasta: Path) {
        val exchange = PackFileExchange(PackStore(storage(root)))

        val failure = assertThrows<PackExchangeException> { exchange.read(pasta.resolve("ausente.json")) }

        assertTrue(failure.message.orEmpty().contains("ausente.json"), failure.message.orEmpty())
    }

    @Test
    fun `arquivo que nao e pack falha antes de qualquer instalacao`(@TempDir root: Path, @TempDir pasta: Path) {
        val store = PackStore(storage(root))
        val arquivo = pasta.resolve("qualquer.json")
        Files.writeString(arquivo, "isto nao e um pack", StandardCharsets.UTF_8)

        val failure = assertThrows<PackExchangeException> { PackFileExchange(store).read(arquivo) }

        assertTrue(failure.message.orEmpty().contains("not a Prumo Pack"), failure.message.orEmpty())
        assertTrue(store.list("destino").isEmpty())
    }

    /** A recusa de exportar segredo é do exportador; o que se prova aqui é que nada é gravado. */
    @Test
    fun `pack que carrega segredo nao chega a virar arquivo`(@TempDir root: Path, @TempDir pasta: Path) {
        val store = PackStore(storage(root))
        store.save(
            "origem",
            manifest().copy(description = LocalizedText("Queries", mapOf("password" to "1234"))),
        )
        val arquivo = pasta.resolve("com-segredo.json")

        assertThrows<PackExchangeException> { PackFileExchange(store).write("origem", "folha-tools", arquivo) }

        assertFalse(Files.exists(arquivo))
    }
}
