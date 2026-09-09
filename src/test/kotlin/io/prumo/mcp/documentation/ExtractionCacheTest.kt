package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * O cache do texto extraído.
 *
 * O que estes testes protegem é a invalidação: cache que serve texto de um arquivo já alterado é
 * pior que não ter cache, porque a resposta errada tem cara de resposta boa.
 */
class ExtractionCacheTest {

    @Test
    fun `o mesmo arquivo nao e extraido duas vezes`(@TempDir root: Path) {
        val arquivo = arquivo(root, "conteudo inicial")
        val cache = ExtractionCache()
        var extracoes = 0

        repeat(3) { cache.getOrExtract(arquivo) { extracoes++; documento("um") } }

        assertEquals(1, extracoes)
        assertEquals(1, cache.size())
    }

    @Test
    fun `arquivo alterado e extraido de novo`(@TempDir root: Path) {
        val arquivo = arquivo(root, "conteudo inicial")
        val cache = ExtractionCache()
        var extracoes = 0

        cache.getOrExtract(arquivo) { extracoes++; documento("antes") }
        Files.writeString(arquivo, "conteudo alterado, e bem maior do que era antes")
        val depois = cache.getOrExtract(arquivo) { extracoes++; documento("depois") }

        assertEquals(2, extracoes)
        assertEquals("depois", depois.lines.single().text)
    }

    /** Mesma data e mesmo tamanho, conteúdo diferente: só o resumo separa os dois. */
    @Test
    fun `conteudo trocado sem mudar tamanho nem data e extraido de novo`(@TempDir root: Path) {
        val arquivo = arquivo(root, "AAAA")
        val data = Files.getLastModifiedTime(arquivo)
        val cache = ExtractionCache()
        var extracoes = 0

        cache.getOrExtract(arquivo) { extracoes++; documento("antes") }
        Files.writeString(arquivo, "BBBB")
        Files.setLastModifiedTime(arquivo, data)
        cache.getOrExtract(arquivo) { extracoes++; documento("depois") }

        assertEquals(2, extracoes, "o cache confiou em tamanho e data, e serviu texto velho")
    }

    @Test
    fun `arquivos diferentes nao compartilham entrada`(@TempDir root: Path) {
        val um = arquivo(root, "um", "um.txt")
        val outro = arquivo(root, "outro", "outro.txt")
        val cache = ExtractionCache()

        assertEquals("um", cache.getOrExtract(um) { documento("um") }.lines.single().text)
        assertEquals("outro", cache.getOrExtract(outro) { documento("outro") }.lines.single().text)
        assertEquals(2, cache.size())
    }

    @Test
    fun `o cache nao cresce sem limite`(@TempDir root: Path) {
        val cache = ExtractionCache(maxDocuments = 2)

        (1..5).forEach { indice ->
            val arquivo = arquivo(root, "conteudo $indice", "arquivo$indice.txt")
            cache.getOrExtract(arquivo) { documento("doc $indice") }
        }

        assertEquals(2, cache.size())
    }

    @Test
    fun `arquivo que sumiu nao e guardado`(@TempDir root: Path) {
        val cache = ExtractionCache()
        var extracoes = 0
        val ausente = root.resolve("nao-existe.txt")

        repeat(2) { cache.getOrExtract(ausente) { extracoes++; documento("nada") } }

        assertEquals(2, extracoes, "sem carimbo não há como saber se o texto ainda descreve a fonte")
        assertEquals(0, cache.size())
    }

    @Test
    fun `cache sem espaco algum e recusado`() {
        assertTrue(runCatching { ExtractionCache(maxDocuments = 0) }.isFailure)
    }

    private fun arquivo(root: Path, conteudo: String, nome: String = "documento.txt"): Path {
        val arquivo = root.resolve(nome)
        Files.writeString(arquivo, conteudo)
        return arquivo
    }

    private fun documento(texto: String) =
        ExtractedDocument(listOf(ExtractedLine(SourceCoordinate.Page(1), texto)))
}
