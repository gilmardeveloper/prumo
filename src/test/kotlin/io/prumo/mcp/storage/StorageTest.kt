package io.prumo.mcp.storage

import io.prumo.mcp.platform.PrumoDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Serializable
private data class SampleDocument(val id: String, val label: String, val enabled: Boolean = true)

class FileSystemStorageProviderTest {

    private fun provider(root: Path) = FileSystemStorageProvider(
        PrumoDirectories(
            config = root.resolve("config"),
            data = root.resolve("data"),
            cache = root.resolve("cache"),
        ),
    )

    @Test
    fun `cria a arvore de diretorios esperada`(@TempDir root: Path) {
        val storage = provider(root)

        storage.prepare()

        assertTrue(Files.isDirectory(storage.workspacesRoot()))
        assertTrue(Files.isDirectory(storage.settingsRoot()))
        assertTrue(Files.isDirectory(storage.clientsRoot()))
        assertTrue(Files.isDirectory(storage.runtimeRoot()))
        assertTrue(Files.isDirectory(storage.cacheRoot()))
    }

    @Test
    fun `o diretorio de um workspace fica sob workspaces`(@TempDir root: Path) {
        val storage = provider(root)

        val workspace = storage.workspaceRoot("modernizacao-folha")

        assertEquals(storage.workspacesRoot().resolve("modernizacao-folha"), workspace)
    }

    @Test
    fun `id de workspace com separador ou travessia e rejeitado`(@TempDir root: Path) {
        val storage = provider(root)

        listOf("..", "../outro", "a/b", "a\\b", "", "a".repeat(65), "espaco proibido").forEach { id ->
            assertThrows(IllegalArgumentException::class.java, { storage.workspaceRoot(id) }, id)
        }
    }
}

class JsonStoreTest {

    private val store = JsonStore()
    private val serializer = serializer<SampleDocument>()

    @Test
    fun `grava e le o mesmo documento`(@TempDir root: Path) {
        val file = root.resolve("nested").resolve("document.json")
        val document = SampleDocument(id = "abc", label = "Folha")

        store.write(file, serializer, document)

        assertEquals(document, store.read(file, serializer))
    }

    @Test
    fun `arquivo ausente devolve nulo em vez de falhar`(@TempDir root: Path) {
        assertNull(store.read(root.resolve("nao-existe.json"), serializer))
    }

    @Test
    fun `arquivo corrompido falha de forma explicita`(@TempDir root: Path) {
        val file = root.resolve("document.json")
        Files.writeString(file, "{ isto nao e json")

        val failure = assertThrows(CorruptedStoreException::class.java) {
            store.read(file, serializer)
        }

        assertTrue(failure.message!!.contains(file.toString()))
    }

    @Test
    fun `sobrescrever nao deixa arquivo temporario para tras`(@TempDir root: Path) {
        val file = root.resolve("document.json")

        store.write(file, serializer, SampleDocument("1", "primeiro"))
        store.write(file, serializer, SampleDocument("2", "segundo"))

        val leftovers = Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals(0L, leftovers)
        assertEquals("2", store.read(file, serializer)?.id)
    }
}
