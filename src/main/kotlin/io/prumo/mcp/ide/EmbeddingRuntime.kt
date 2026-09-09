package io.prumo.mcp.ide

import ai.onnxruntime.OrtEnvironment
import com.intellij.openapi.diagnostic.logger

/**
 * O motor de inferência local, e se ele está de pé nesta instalação.
 *
 * A biblioteca extrai uma biblioteca nativa por plataforma ao ser carregada, e isso pode falhar por
 * motivos que não são do produto — plataforma sem binário, diretório temporário sem permissão,
 * política da máquina. Falhar aqui não pode derrubar nada: sem o motor, a busca continua por
 * palavra, e o produto diz que a metade semântica não está disponível.
 */
object EmbeddingRuntime {

    private val log = logger<EmbeddingRuntime>()

    private val state: Result<OrtEnvironment> by lazy {
        runCatching { OrtEnvironment.getEnvironment() }
            .onFailure { log.warn("O motor de inferencia local nao carregou nesta instalacao.", it) }
    }

    /** Se o motor carregou. Consultar não força nada além do carregamento, que é feito uma vez. */
    val available: Boolean get() = state.isSuccess

    /** O motivo de o motor não estar disponível, ou nulo quando ele está. */
    val unavailableReason: String?
        get() = state.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message.orEmpty()}" }

    /** O ambiente de inferência, ou nulo quando ele não carregou. */
    fun environmentOrNull(): OrtEnvironment? = state.getOrNull()
}
