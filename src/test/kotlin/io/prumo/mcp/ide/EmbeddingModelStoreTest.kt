package io.prumo.mcp.ide

import io.prumo.mcp.platform.PrumoDirectories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A guarda do modelo local.
 *
 * Duas garantias: nada vem da rede sem o desenvolvedor mandar, e nada que a rede trouxe é aceito sem
 * conferência — arquivo baixado sem conferir resumo é código de origem desconhecida na máquina de
 * quem programa.
 */
@Tag("security")
class EmbeddingModelStoreTest {

    private val conteudo = "pesos do modelo, de mentira".toByteArray()

    @Test
    fun `consultar o estado nao busca nada na rede`(@TempDir root: Path) {
        var buscas = 0
        val store = EmbeddingModelStore(directories(root)) { buscas++; ByteArrayInputStream(conteudo) }

        val estado = store.state(descritor())

        assertFalse(estado.installed)
        assertEquals(0, buscas, "o estado foi consultado e o Prumo saiu para a rede")
    }

    @Test
    fun `o modelo instalado fica no diretorio do proprio Prumo`(@TempDir root: Path) {
        val store = EmbeddingModelStore(directories(root)) { ByteArrayInputStream(conteudo) }

        val estado = store.install(descritor())

        assertTrue(estado.installed)
        assertEquals(conteudo.size.toLong() * 2, estado.sizeBytes, "o tamanho soma modelo e tokenizador")
        assertTrue(estado.tokenizerPath!!.startsWith(root.resolve("cache")), estado.tokenizerPath.toString())
        assertTrue(estado.path!!.startsWith(root.resolve("cache")), estado.path.toString())
    }

    @Test
    fun `resumo divergente recusa a instalacao e nao deixa sobra`(@TempDir root: Path) {
        val store = EmbeddingModelStore(directories(root)) { ByteArrayInputStream(conteudo) }

        val falha = assertThrows<ModelInstallException> {
            store.install(descritor(sha256 = "sha256:0000000000000000000000000000000000000000000000000000000000000000"))
        }

        assertTrue(falha.message.orEmpty().contains("checksum"), falha.message.orEmpty())
        assertFalse(store.state(descritor()).installed)
        assertTrue(sobras(root).isEmpty(), "ficou arquivo pela metade: ${sobras(root)}")
    }

    @Test
    fun `busca interrompida nao deixa meio modelo passando por modelo inteiro`(@TempDir root: Path) {
        val store = EmbeddingModelStore(directories(root)) { throw java.io.IOException("conexao caiu") }

        val falha = assertThrows<ModelInstallException> { store.install(descritor()) }

        assertTrue(falha.message.orEmpty().contains("could not download"), falha.message.orEmpty())
        assertFalse(store.state(descritor()).installed)
        assertTrue(sobras(root).isEmpty(), "ficou arquivo pela metade: ${sobras(root)}")
    }

    @Test
    fun `instalar duas vezes nao baixa duas vezes`(@TempDir root: Path) {
        var buscas = 0
        val store = EmbeddingModelStore(directories(root)) { buscas++; ByteArrayInputStream(conteudo) }

        store.install(descritor())
        store.install(descritor())

        assertEquals(2, buscas, "cada arquivo é buscado uma vez, e nenhum é buscado duas")
    }

    @Test
    fun `remover apaga o modelo, e remover de novo nao mente`(@TempDir root: Path) {
        val store = EmbeddingModelStore(directories(root)) { ByteArrayInputStream(conteudo) }
        store.install(descritor())

        assertTrue(store.remove(descritor()))
        assertFalse(store.state(descritor()).installed)
        assertFalse(store.remove(descritor()))
    }

    private fun sobras(root: Path): List<Path> {
        val pasta = root.resolve("cache").resolve("models")
        if (!Files.isDirectory(pasta)) return emptyList()
        return Files.list(pasta).use { it.toList() }
    }

    private fun directories(root: Path) = PrumoDirectories(
        config = root.resolve("config"),
        data = root.resolve("data"),
        cache = root.resolve("cache"),
    )

    private fun descritor(sha256: String = resumoDe(conteudo)) = ModelDescriptor(
        id = "multilingual-e5-small-int8",
        uri = URI("https://exemplo.invalido/modelo.onnx"),
        sha256 = sha256,
        sizeBytes = conteudo.size.toLong(),
        tokenizerUri = URI("https://exemplo.invalido/tokenizer.json"),
        tokenizerSha256 = resumoDe(conteudo),
        tokenizerSizeBytes = conteudo.size.toLong(),
    )

    private fun resumoDe(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
