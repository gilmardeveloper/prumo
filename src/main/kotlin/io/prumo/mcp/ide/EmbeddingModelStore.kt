package io.prumo.mcp.ide

import io.prumo.mcp.platform.PrumoDirectories
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Falha ao instalar o modelo local, com a mensagem que chega ao desenvolvedor. */
class ModelInstallException(message: String) : RuntimeException(message)

/**
 * O modelo local que o Prumo sabe buscar, e como reconhecê-lo.
 *
 * O resumo é obrigatório: arquivo baixado da rede sem conferência é código de origem desconhecida
 * entrando na máquina de quem programa.
 *
 * @property sizeBytes tamanho declarado, dito ao desenvolvedor **antes** de baixar.
 */
data class ModelDescriptor(
    val id: String,
    val uri: URI,
    val sha256: String,
    val sizeBytes: Long,
    /** O tokenizador do mesmo modelo. Sem ele o texto não vira a sequência que a rede espera. */
    val tokenizerUri: URI,
    val tokenizerSha256: String,
    val tokenizerSizeBytes: Long,
) {
    /** O que se baixa ao todo, dito ao desenvolvedor antes de baixar. */
    val totalBytes: Long get() = sizeBytes + tokenizerSizeBytes
}

/** O que existe hoje na máquina. */
data class ModelState(
    val installed: Boolean,
    val id: String? = null,
    val sizeBytes: Long = 0,
    val path: Path? = null,
    val tokenizerPath: Path? = null,
)

/**
 * Guarda o modelo de embedding na máquina do desenvolvedor.
 *
 * O modelo não vem dentro do plugin e não é instalado por fora: quem o busca é o próprio Prumo,
 * quando o desenvolvedor manda, e para o diretório de cache do produto — nunca para dentro de um
 * repositório do usuário.
 *
 * Nada aqui baixa sozinho. Consultar o estado não busca nada; só [install] vai à rede, e só depois
 * de o tamanho ter sido declarado.
 */
class EmbeddingModelStore(
    private val directories: PrumoDirectories,
    private val open: (URI) -> InputStream = { it.toURL().openStream() },
) {

    private val folder: Path get() = directories.cache.resolve(MODELS)

    /** O que está instalado agora, sem tocar a rede. */
    fun state(descriptor: ModelDescriptor): ModelState {
        val file = fileOf(descriptor)
        val tokenizer = tokenizerOf(descriptor)
        if (!Files.isRegularFile(file) || !Files.isRegularFile(tokenizer)) {
            return ModelState(installed = false)
        }
        return ModelState(
            installed = true,
            id = descriptor.id,
            sizeBytes = Files.size(file) + Files.size(tokenizer),
            path = file,
            tokenizerPath = tokenizer,
        )
    }

    /**
     * Busca o modelo e o guarda, se o resumo conferir.
     *
     * O arquivo é escrito ao lado, com nome temporário, e só toma o lugar do definitivo depois da
     * conferência: download interrompido não deixa meio modelo passando por modelo inteiro.
     *
     * @throws ModelInstallException quando a busca falha ou o resumo não confere.
     */
    fun install(descriptor: ModelDescriptor): ModelState {
        val existente = state(descriptor)
        if (existente.installed) {
            return existente
        }

        Files.createDirectories(folder)
        fetch(descriptor.uri, fileOf(descriptor), descriptor.sha256, "model")
        fetch(descriptor.tokenizerUri, tokenizerOf(descriptor), descriptor.tokenizerSha256, "tokenizer")
        return state(descriptor)
    }

    private fun fetch(uri: URI, alvo: Path, sha256: String, o_que: String) {
        if (Files.isRegularFile(alvo)) {
            return
        }
        val parcial = Path.of(alvo.toString() + PARTIAL)
        try {
            open(uri).use { entrada -> Files.copy(entrada, parcial, StandardCopyOption.REPLACE_EXISTING) }
            if (!digestOf(parcial).equals(sha256, ignoreCase = true)) {
                throw ModelInstallException(
                    "The downloaded $o_que does not match the expected checksum and was discarded.",
                )
            }
            Files.move(parcial, alvo, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: ModelInstallException) {
            Files.deleteIfExists(parcial)
            throw failure
        } catch (failure: Exception) {
            Files.deleteIfExists(parcial)
            throw ModelInstallException("Prumo could not download the $o_que: ${failure.message.orEmpty()}")
        }
    }

    /** Apaga o modelo e o tokenizador. Devolve falso quando não havia nada para apagar. */
    fun remove(descriptor: ModelDescriptor): Boolean {
        val modelo = Files.deleteIfExists(fileOf(descriptor))
        val tokenizador = Files.deleteIfExists(tokenizerOf(descriptor))
        return modelo || tokenizador
    }

    private fun fileOf(descriptor: ModelDescriptor): Path = folder.resolve("${descriptor.id}$MODEL_SUFFIX")

    private fun tokenizerOf(descriptor: ModelDescriptor): Path =
        folder.resolve("${descriptor.id}$TOKENIZER_SUFFIX")

    private fun digestOf(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODELS = "models"
        const val MODEL_SUFFIX = ".onnx"
        const val TOKENIZER_SUFFIX = ".tokenizer.json"
        const val PARTIAL = ".partial"
    }
}
