package io.prumo.mcp.ide

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import io.prumo.mcp.documentation.TextEmbedder
import io.prumo.mcp.documentation.l2Normalize
import io.prumo.mcp.documentation.meanPool
import java.nio.LongBuffer
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
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : TextEmbedder, AutoCloseable {

    private val environment = requireNotNull(EmbeddingRuntime.environmentOrNull()) {
        "The local inference engine did not load in this installation."
    }

    private val tokenizer: HuggingFaceTokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile)

    private val session: OrtSession = environment.createSession(
        modelFile.toString(),
        OrtSession.SessionOptions(),
    )

    override val dimensions: Int by lazy { embed(listOf("a")).single().size }

    override fun embed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "There is nothing to embed." }

        val codificados = texts.map { tokenizer.encode(it.take(MAX_CHARS)) }
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

    private fun tensor(valores: LongArray, forma: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(valores), forma)

    private companion object {
        /** Sequência máxima que se manda ao modelo. Trecho maior é cortado, não recusado. */
        const val DEFAULT_MAX_TOKENS = 512

        /** Corte de caracteres antes de tokenizar, para não pagar tokenização do que seria descartado. */
        const val MAX_CHARS = 4_000
    }
}
