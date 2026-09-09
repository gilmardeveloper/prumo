package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A montagem dos pedaços de uma fonte inteira.
 *
 * O que estes testes protegem é o endereço de volta: o pedaço precisa saber de qual arquivo da
 * pasta ele veio, senão a busca acha o trecho e ninguém sabe onde lê-lo por inteiro.
 */
class DocumentationIndexerTest {

    @Test
    fun `fonte de arquivo unico vira pedacos daquele arquivo`(@TempDir root: Path) {
        val arquivo = root.resolve("regras.md")
        Files.writeString(arquivo, "conteudo")

        val pedacos = chunksOfSource(fonte("regras", arquivo), leitor())

        assertEquals(1, pedacos.size)
        assertEquals("regras", pedacos.single().documentationId)
        assertEquals("regras.md", pedacos.single().path)
    }

    @Test
    fun `fonte de pasta guarda o caminho relativo de cada arquivo`(@TempDir root: Path) {
        val pasta = root.resolve("docs")
        Files.createDirectories(pasta.resolve("eventos"))
        Files.writeString(pasta.resolve("manual.md"), "manual")
        Files.writeString(pasta.resolve("eventos/S-1200.md"), "evento")

        val pedacos = chunksOfSource(fonte("docs", pasta), leitor())

        assertEquals(listOf("eventos/S-1200.md", "manual.md"), pedacos.map { it.path }.sorted())
    }

    @Test
    fun `arquivo de formato que o Prumo nao le fica de fora`(@TempDir root: Path) {
        val pasta = root.resolve("docs")
        Files.createDirectories(pasta)
        Files.writeString(pasta.resolve("manual.md"), "manual")
        Files.writeString(pasta.resolve("imagem.png"), "binario")

        val pedacos = chunksOfSource(fonte("docs", pasta), leitor())

        assertEquals(listOf("manual.md"), pedacos.map { it.path })
    }

    /** Um arquivo que falha ao extrair não pode derrubar a indexação da fonte inteira. */
    @Test
    fun `arquivo que nao abre nao derruba os outros`(@TempDir root: Path) {
        val pasta = root.resolve("docs")
        Files.createDirectories(pasta)
        Files.writeString(pasta.resolve("bom.md"), "conteudo")
        Files.writeString(pasta.resolve("ruim.md"), "conteudo")

        val pedacos = chunksOfSource(
            fonte("docs", pasta),
            read = { arquivo ->
                if (arquivo.fileName.toString() == "ruim.md") {
                    throw DocumentationReadException("não abre")
                }
                ExtractedDocument(listOf(ExtractedLine(SourceCoordinate.Paragraph(1), "conteudo")))
            },
        )

        assertEquals(listOf("bom.md"), pedacos.map { it.path })
    }

    @Test
    fun `fonte que sumiu nao vira pedaco nenhum`(@TempDir root: Path) {
        val pedacos = chunksOfSource(fonte("sumida", root.resolve("nao-existe")), leitor())

        assertTrue(pedacos.isEmpty())
    }

    /**
     * Sem a receita na assinatura, mudar o corte ou o prefixo deixaria o acervo metade na receita
     * velha e metade na nova, e a ordem devolvida não teria sentido.
     */
    @Test
    fun `a assinatura carrega a receita de indexacao`(@TempDir root: Path) {
        val arquivo = root.resolve("regras.md")
        Files.writeString(arquivo, "conteudo")

        val assinatura = signatureOfSource(fonte("regras", arquivo))

        assertTrue(assinatura.startsWith(INDEX_RECIPE), assinatura)
    }

    @Test
    fun `a assinatura muda quando o arquivo muda`(@TempDir root: Path) {
        val arquivo = root.resolve("regras.md")
        Files.writeString(arquivo, "conteudo")
        val antes = signatureOfSource(fonte("regras", arquivo))

        Files.writeString(arquivo, "conteudo bem maior do que era antes")

        assertTrue(antes != signatureOfSource(fonte("regras", arquivo)))
    }

    private fun leitor(): (Path) -> ExtractedDocument = { arquivo ->
        ExtractedDocument(
            listOf(ExtractedLine(SourceCoordinate.Paragraph(1), "texto de ${arquivo.fileName}")),
        )
    }

    private fun fonte(id: String, local: Path) = DocumentationSource(
        id = id,
        name = id,
        kind = if (Files.isDirectory(local)) DocumentationKind.DIRECTORY else DocumentationKind.FILE,
        location = local.toString(),
    )
}
