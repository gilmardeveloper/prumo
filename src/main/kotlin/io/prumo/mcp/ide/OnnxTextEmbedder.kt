package io.prumo.mcp.ide

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import io.prumo.mcp.documentation.TextEmbedder
import io.prumo.mcp.documentation.l2Normalize
import io.prumo.mcp.documentation.meanPool
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Embutidor local, rodando na máquina do desenvolvedor.
 *
 * O modelo e o tokenizador vêm do armazenamento do Prumo, baixados sob comando; o motor vem
 * empacotado com o plugin. Nada do texto sai da máquina: a inferência é local, e depois do download
 * inicial não há rede envolvida.
 *
 * A sessão é aberta uma vez e reaproveitada — abri-la custa perto de duzentos milésimos de segundo,
 * e uma sessão por chamada dominaria o custo de indexar.
 */
class OnnxTextEmbedder(
    modelFile: Path,
    tokenizerFile: Path,
    nativeCache: Path,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : TextEmbedder, AutoCloseable {

    private val environment = requireNotNull(EmbeddingRuntime.environmentOrNull()) {
        "The local inference engine did not load in this installation."
    }

    private val tokenizer: HuggingFaceTokenizer = withPluginClassLoader {
        prepareNativeCache(nativeCache)
        HuggingFaceTokenizer.newInstance(tokenizerFile)
    }

    private val session: OrtSession = environment.createSession(
        modelFile.toString(),
        OrtSession.SessionOptions(),
    )

    override val dimensions: Int by lazy { embed(listOf("a")).single().size }

    override fun embed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "There is nothing to embed." }

        val codificados = withPluginClassLoader { texts.map { tokenizer.encode(it.take(MAX_CHARS)) } }
        val comprimento = codificados.maxOf { it.ids.size }.coerceAtMost(maxTokens).coerceAtLeast(1)
        val ids = LongArray(texts.size * comprimento)
        val mascara = LongArray(texts.size * comprimento)
        codificados.forEachIndexed { linha, codificado ->
            val quantos = minOf(codificado.ids.size, comprimento)
            for (coluna in 0 until quantos) {
                ids[linha * comprimento + coluna] = codificado.ids[coluna]
                mascara[linha * comprimento + coluna] = codificado.attentionMask[coluna]
            }
        }

        val forma = longArrayOf(texts.size.toLong(), comprimento.toLong())
        val entradas = mutableMapOf<String, OnnxTensor>()
        try {
            session.inputNames.forEach { nome ->
                entradas[nome] = when {
                    nome.contains("attention_mask") -> tensor(mascara, forma)
                    nome.contains("token_type") -> tensor(LongArray(ids.size), forma)
                    else -> tensor(ids, forma)
                }
            }
            session.run(entradas).use { saida ->
                @Suppress("UNCHECKED_CAST")
                val porToken = saida[0].value as Array<Array<FloatArray>>
                return porToken.mapIndexed { linha, tokens ->
                    val recorte = mascara.copyOfRange(linha * comprimento, (linha + 1) * comprimento)
                    l2Normalize(meanPool(tokens.toList(), recorte))
                }
            }
        } finally {
            entradas.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    /**
     * Deixa o cache nativo do tokenizador dentro do diretório do Prumo, e íntegro.
     *
     * A biblioteca extrai os binários para um subdiretório temporário e carrega dali na mesma
     * execução, mas não o promove: a sessão seguinte encontra a pasta da versão existindo, procura os
     * binários na raiz dela e falha com "Can't load library: …\libwinpthread-1.dll" — medido dentro
     * da IDE, três vezes. Por isso a pasta sem binário na raiz é apagada antes de a biblioteca olhar
     * para ela, o que na prática significa reextrair a cada sessão. Não é desperdício a ser
     * otimizado: é o que faz a busca por sentido voltar a funcionar depois do primeiro reinício.
     */
    private fun prepareNativeCache(root: Path) {
        System.setProperty(DJL_CACHE_PROPERTY, root.toString())
        val tokenizers = root.resolve("tokenizers")
        if (!Files.isDirectory(tokenizers)) {
            return
        }
        val incompleto = Files.list(tokenizers).use { versoes ->
            versoes.filter { Files.isDirectory(it) }.anyMatch { versao ->
                Files.list(versao).use { arquivos ->
                    arquivos.noneMatch { it.fileName.toString().startsWith(NATIVE_PREFIX) }
                }
            }
        }
        if (incompleto) {
            tokenizers.toFile().deleteRecursively()
        }
    }

    /**
     * Roda o bloco com o classloader do plugin no lugar do classloader de contexto da thread.
     *
     * O tokenizador descobre qual biblioteca nativa carregar lendo um arquivo de propriedades pelo
     * classloader de contexto. Numa thread de fundo da IDE esse classloader é o da plataforma, que
     * não enxerga o jar do plugin, e a busca por sentido morria com "No tokenizers version found in
     * property file" — medido dentro da IDE, não suposto.
     */
    private fun <T> withPluginClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val anterior = thread.contextClassLoader
        thread.contextClassLoader = OnnxTextEmbedder::class.java.classLoader
        try {
            return block()
        } finally {
            thread.contextClassLoader = anterior
        }
    }

    private fun tensor(valores: LongArray, forma: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(valores), forma)

    internal companion object {
        /** Sequência máxima que se manda ao modelo. Trecho maior é cortado, não recusado. */
        const val DEFAULT_MAX_TOKENS = 512

        /** Onde a biblioteca do tokenizador guarda o que extrai. */
        const val DJL_CACHE_PROPERTY = "DJL_CACHE_DIR"

        /** Como se chama o binário que prova que a extração terminou. */
        const val NATIVE_PREFIX = "tokenizers."

        /** Corte de caracteres antes de tokenizar, para não pagar tokenização do que seria descartado. */
        const val MAX_CHARS = 4_000
    }
}
