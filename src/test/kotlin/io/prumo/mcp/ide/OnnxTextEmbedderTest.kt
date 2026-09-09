package io.prumo.mcp.ide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * O embutidor rodando de verdade, com o modelo local.
 *
 * Pulado quando o modelo não está na máquina, porque ele é baixado sob comando e não vive no
 * repositório. O que estes testes provam é o que a busca por sentido precisa: o mesmo texto sempre
 * dá o mesmo vetor, e o que é próximo em significado fica próximo em direção — mesmo sem
 * compartilhar palavra nenhuma.
 */
class OnnxTextEmbedderTest {

    private val pasta = Path.of(
        "C:/Users/hexp_/AppData/Local/Temp/claude/C--projetos-gilmar-Prumo/" +
            "758eee8c-d1b8-493f-96cd-9e64c9c4a109/scratchpad/modelo",
    )

    @Test
    fun `o mesmo texto devolve sempre o mesmo vetor`() {
        embutidor { embutidor ->
            val primeiro = embutidor.embed(listOf("rubrica com incidência suspensa")).single()
            val segundo = embutidor.embed(listOf("rubrica com incidência suspensa")).single()

            assertTrue(primeiro.contentEquals(segundo), "o mesmo texto produziu vetores diferentes")
        }
    }

    /** É a razão de existir da onda: achar sem compartilhar palavra. */
    @Test
    fun `sinonimo fica mais proximo do que assunto alheio`() {
        embutidor { embutidor ->
            val vetores = embutidor.embed(
                listOf(
                    "o colaborador recebe adicional de insalubridade",
                    "o servidor tem direito a adicional de insalubridade",
                    "o docker-compose sobe uma réplica na porta 3030",
                ),
            )

            val entreSinonimos = cosseno(vetores[0], vetores[1])
            val entreAssuntos = cosseno(vetores[0], vetores[2])

            assertTrue(
                entreSinonimos > entreAssuntos,
                "sinônimos ficaram mais longe que assunto alheio: $entreSinonimos vs $entreAssuntos",
            )
        }
    }

    @Test
    fun `o vetor sai normalizado e com a dimensao do modelo`() {
        embutidor { embutidor ->
            val vetor = embutidor.embed(listOf("qualquer texto")).single()

            assertEquals(384, vetor.size, "dimensão inesperada para este modelo")
            assertEquals(1f, cosseno(vetor, vetor), 1e-5f)
            assertEquals(384, embutidor.dimensions)
        }
    }

    @Test
    fun `lote devolve na mesma ordem em que entrou`() {
        embutidor { embutidor ->
            val separados = listOf("primeiro texto", "segundo texto").map { embutidor.embed(listOf(it)).single() }
            val emLote = embutidor.embed(listOf("primeiro texto", "segundo texto"))

            assertTrue(cosseno(separados[0], emLote[0]) > 0.99f, "o lote trocou a ordem ou mudou o resultado")
            assertTrue(cosseno(separados[1], emLote[1]) > 0.99f)
        }
    }

    @Test
    fun `texto muito maior que a janela nao quebra`() {
        embutidor { embutidor ->
            val vetor = embutidor.embed(listOf("rubrica ".repeat(5_000))).single()

            assertEquals(384, vetor.size)
        }
    }

    @Test
    fun `lote vazio e recusado`() {
        embutidor { embutidor ->
            assertTrue(runCatching { embutidor.embed(emptyList()) }.isFailure)
        }
    }

    /**
     * Medido dentro da IDE: o tokenizador descobre a biblioteca nativa lendo um arquivo de
     * propriedades pelo classloader de **contexto da thread**, e numa thread de fundo da IDE esse
     * classloader não enxerga o jar do plugin. Aqui a hostilidade é reproduzida trocando o
     * classloader de contexto antes de abrir o embutidor.
     */
    @Test
    fun `abre com o classloader de contexto trocado por um que nao enxerga a biblioteca`() {
        val thread = Thread.currentThread()
        val anterior = thread.contextClassLoader
        thread.contextClassLoader = ClassLoader.getPlatformClassLoader()
        try {
            embutidor { embutidor ->
                assertEquals(384, embutidor.embed(listOf("rubrica")).single().size)
            }
        } finally {
            thread.contextClassLoader = anterior
        }
    }

    /**
     * O teste acima não distingue o código corrigido do código antigo quando a biblioteca já foi
     * inicializada por outro teste da mesma JVM. O que está preso aqui é a troca em si.
     */
    @Test
    fun `o embutidor troca o classloader antes de falar com o tokenizador`() {
        val fonte = Files.readString(Path.of("src/main/kotlin/io/prumo/mcp/ide/OnnxTextEmbedder.kt"))

        assertTrue(
            fonte.contains("thread.contextClassLoader = OnnxTextEmbedder::class.java.classLoader"),
            "o embutidor voltou a confiar no classloader de contexto da thread",
        )
        val criacao = fonte.substringAfter("private val tokenizer").substringBefore("private val session")
        assertTrue(
            criacao.contains("withPluginClassLoader") && criacao.contains("HuggingFaceTokenizer.newInstance"),
            "a criação do tokenizador saiu de dentro da troca de classloader: $criacao",
        )
    }

    /**
     * A extração interrompida deixa a pasta da versão existindo com os binários um nível abaixo, e a
     * biblioteca passa a considerá-la pronta. Medido dentro da IDE: falha para sempre, até a pasta
     * ser apagada à mão.
     */
    @Test
    fun `pasta de binarios pela metade e apagada antes de a biblioteca olhar`(@TempDir cache: Path) {
        val versao = cache.resolve("tokenizers").resolve("0.0.0-cpu-win-x86_64").resolve("86")
        Files.createDirectories(versao)
        Files.writeString(versao.resolve("tokenizers.dll"), "binario no lugar errado")

        val modelo = pasta.resolve("model_quantized.onnx")
        val tokenizador = pasta.resolve("tokenizer.json")
        assumeTrue(Files.exists(modelo) && Files.exists(tokenizador), "modelo local ausente nesta máquina")
        assumeTrue(EmbeddingRuntime.available, EmbeddingRuntime.unavailableReason.orEmpty())

        OnnxTextEmbedder(modelo, tokenizador, cache).use {
            assertTrue(
                !Files.exists(cache.resolve("tokenizers").resolve("0.0.0-cpu-win-x86_64").resolve("86")),
                "a pasta pela metade sobreviveu, e a biblioteca vai tropeçar nela para sempre",
            )
        }
    }

    private fun embutidor(bloco: (OnnxTextEmbedder) -> Unit) {
        val modelo = pasta.resolve("model_quantized.onnx")
        val tokenizador = pasta.resolve("tokenizer.json")
        assumeTrue(Files.exists(modelo) && Files.exists(tokenizador), "modelo local ausente nesta máquina")
        assumeTrue(EmbeddingRuntime.available, EmbeddingRuntime.unavailableReason.orEmpty())

        OnnxTextEmbedder(modelo, tokenizador, pasta.resolve("djl")).use(bloco)
    }

    private fun cosseno(um: FloatArray, outro: FloatArray): Float =
        um.indices.fold(0f) { soma, indice -> soma + um[indice] * outro[indice] }
}
