package io.prumo.mcp.pack

import io.prumo.mcp.pack.application.PackAccessException
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.KnowledgeItem
import io.prumo.mcp.pack.domain.LocalizedText
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.policy.Capability
import io.prumo.mcp.repository.PathAccessDeniedException
import io.prumo.mcp.storage.FileSystemStorageProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("security")
class PackStoreTest {

    private fun storage(root: Path) = FileSystemStorageProvider(
        PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
    )

    private fun manifest(
        id: String = "folha-conhecimento",
        knowledge: List<KnowledgeItem> = listOf(
            KnowledgeItem(
                id = "rubricas",
                title = LocalizedText("Payroll rules", mapOf("pt-BR" to "Regras de rubrica")),
                file = "knowledge/rubricas.md",
                tags = listOf("folha", "rubrica"),
            ),
        ),
    ) = PackManifest(
        id = id,
        version = "1.0.0",
        title = LocalizedText("Payroll knowledge", mapOf("pt-BR" to "Conhecimento da folha")),
        description = LocalizedText("Business rules of the payroll system"),
        author = "Equipe folha",
        capabilities = setOf(Capability.DOCUMENTATION_READ),
        knowledge = knowledge,
        createdAt = "2026-09-05T00:00:00Z",
        updatedAt = "2026-09-05T00:00:00Z",
    )

    private fun install(store: PackStore, workspaceId: String, manifest: PackManifest, content: String) {
        store.save(workspaceId, manifest)
        manifest.knowledge.forEach { store.writeKnowledge(workspaceId, manifest.id, it, content) }
    }

    @Test
    fun `pack instalado sobrevive ao ciclo de gravacao`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "# Rubricas\nA rubrica 101 e vencimento base.")

        val loaded = store.load("folha-2026", "folha-conhecimento")

        assertEquals("1.0.0", loaded?.version)
        assertEquals(setOf(Capability.DOCUMENTATION_READ), loaded?.capabilities)
        assertEquals("Conhecimento da folha", loaded?.title?.forLanguage("pt-BR"))
        assertEquals("Payroll knowledge", loaded?.title?.forLanguage("de"))
    }

    @Test
    fun `pack de um workspace nao aparece no outro`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "conteudo")

        assertEquals(listOf("folha-conhecimento"), store.list("folha-2026").map { it.id })
        assertTrue(store.list("outro-workspace").isEmpty(), "o pack de um workspace nao existe no outro")
        assertEquals(null, store.load("outro-workspace", "folha-conhecimento"))
    }

    @Test
    fun `nada e escrito fora do diretorio do workspace`(@TempDir root: Path, @TempDir projeto: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "conteudo")

        val dentroDoWorkspace = storage(root).workspaceRoot("folha-2026").resolve("packs")
        assertTrue(Files.isDirectory(dentroDoWorkspace))
        assertTrue(
            Files.list(projeto).use { it.toList().isEmpty() },
            "o repositorio do usuario nao recebe byte algum",
        )
    }

    @Test
    fun `busca acha por titulo, etiqueta e corpo, sempre na mesma ordem`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "Linha um\nA rubrica 101 e vencimento base.\nLinha tres")

        val porCorpo = store.searchKnowledge("folha-2026", "vencimento base")
        val porEtiqueta = store.searchKnowledge("folha-2026", "rubrica")

        assertEquals(1, porCorpo.size)
        assertEquals(2, porCorpo.single().line)
        assertTrue(porCorpo.single().excerpt.contains("vencimento base"))
        // Titulo (3) + etiqueta (2) + corpo (1): a pontuacao e explicavel, nao um numero opaco.
        assertEquals(6, porEtiqueta.single().score)
        assertEquals(porEtiqueta, store.searchKnowledge("folha-2026", "rubrica"), "busca determinística")
    }

    @Test
    fun `busca nao alcanca pack de outro workspace`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "segredo do time da folha")

        assertTrue(store.searchKnowledge("outro-workspace", "segredo").isEmpty())
    }

    @Test
    fun `manifesto que aponta para fora do pack e recusado`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        val escapando = manifest(
            knowledge = listOf(
                KnowledgeItem("fuga", LocalizedText("Fuga"), "../../../../etc/passwd"),
            ),
        )
        store.save("folha-2026", escapando)

        assertThrows<PathAccessDeniedException> {
            store.readKnowledge("folha-2026", "folha-conhecimento", "fuga")
        }
    }

    @Test
    fun `item inexistente falha com mensagem acionavel`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "conteudo")

        val failure = assertThrows<PackAccessException> {
            store.readKnowledge("folha-2026", "folha-conhecimento", "inexistente")
        }
        assertTrue(failure.message.orEmpty().contains("inexistente"))
    }

    @Test
    fun `remover o pack nao deixa residuo`(@TempDir root: Path) {
        val store = PackStore(storage(root))
        install(store, "folha-2026", manifest(), "conteudo")

        store.remove("folha-2026", "folha-conhecimento")

        assertTrue(store.list("folha-2026").isEmpty())
        assertFalse(Files.exists(storage(root).workspaceRoot("folha-2026").resolve("packs/folha-conhecimento")))
    }
}

class LocalizedTextTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `texto simples no arquivo continua valendo`() {
        val decoded = json.decodeFromString(serializer<LocalizedText>(), "\"Payroll rules\"")

        assertEquals("Payroll rules", decoded.default)
        assertTrue(decoded.translations.isEmpty())
        assertEquals("Payroll rules", decoded.forLanguage("pt-BR"))
    }

    @Test
    fun `texto com traducoes escolhe o idioma pedido`() {
        val decoded = json.decodeFromString(
            serializer<LocalizedText>(),
            """{"default":"Payroll rules","pt-BR":"Regras da folha"}""",
        )

        assertEquals("Regras da folha", decoded.forLanguage("pt-BR"))
        assertEquals("Regras da folha", decoded.forLanguage("pt"), "cai no idioma quando falta a regiao")
        assertEquals("Payroll rules", decoded.forLanguage("fr"), "sem traducao, o ingles responde")
        assertEquals("Payroll rules", decoded.forLanguage(null))
    }

    @Test
    fun `texto sem ingles e recusado na leitura`() {
        assertThrows<Exception> {
            json.decodeFromString(serializer<LocalizedText>(), """{"pt-BR":"Só português"}""")
        }
    }

    @Test
    fun `gravar e ler devolve o mesmo texto`() {
        val original = LocalizedText("Payroll", mapOf("pt-BR" to "Folha"))

        val encoded = json.encodeToString(serializer<LocalizedText>(), original)
        val decoded = json.decodeFromString(serializer<LocalizedText>(), encoded)

        assertEquals(original, decoded)
        assertEquals("\"Folha simples\"", json.encodeToString(serializer<LocalizedText>(), LocalizedText("Folha simples")))
    }
}
