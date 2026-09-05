package io.prumo.mcp.platform

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Falha esperada ao executar um comando de pack. A mensagem não carrega caminho de máquina. */
class ProcessExecutionException(message: String) : IllegalStateException(message)

data class ProcessRequest(
    /** Comando já quebrado em argumentos. Nunca uma linha de shell: não existe interpretador no meio. */
    val command: List<String>,
    val workingDirectory: Path,
    val timeoutSeconds: Int,
    /** Variáveis extras do pack. O ambiente do processo da IDE **não** é herdado. */
    val environment: Map<String, String> = emptyMap(),
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(command.isNotEmpty()) { "A command is required." }
        require(timeoutSeconds in 1..MAX_TIMEOUT_SECONDS) { "Invalid timeout." }
        require(maxOutputBytes in 1..MAX_OUTPUT_CEILING) { "Invalid output limit." }
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024
        const val MAX_OUTPUT_CEILING = 1024 * 1024
        const val MAX_TIMEOUT_SECONDS = 300
    }
}

data class ProcessOutcome(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMillis: Long,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Executa um comando com confinamento.
 *
 * O que o confinamento garante:
 * - o comando é uma lista de argumentos, nunca uma linha de shell — não há interpretador para
 *   emendar `; rm -rf` no meio;
 * - o diretório de trabalho é o que o chamador determinou;
 * - o ambiente **não** é herdado do processo da IDE: nada de token de nuvem, chave de API ou
 *   variável de sessão vaza para o script. Só entra o mínimo para um programa achar o sistema, mais
 *   o que o pack declarou;
 * - a entrada padrão é fechada, para o processo não travar esperando digitação;
 * - saída tem teto e a resposta diz quando cortou;
 * - tempo tem teto, e o estouro mata o processo e os filhos dele.
 *
 * O que ele **não** garante, e precisa estar dito: o processo roda com o mesmo usuário e as mesmas
 * permissões da IDE. Um comando com caminho absoluto alcança o disco inteiro. Confinamento real de
 * sistema de arquivos exigiria sandbox do sistema operacional, que não existe de forma portátil —
 * por isso a barreira anterior é o consentimento informado sobre o comando exato (seção 8.5).
 */
class ProcessExecutor(
    private val environmentProbe: EnvironmentProbe = SystemEnvironmentProbe,
) {

    fun run(request: ProcessRequest): ProcessOutcome {
        if (!Files.isDirectory(request.workingDirectory)) {
            throw ProcessExecutionException("The working directory of this tool does not exist.")
        }

        val builder = ProcessBuilder(request.command)
            .directory(request.workingDirectory.toFile())
        builder.environment().apply {
            clear()
            putAll(minimalEnvironment(environmentProbe))
            putAll(request.environment)
        }

        val startedAt = System.nanoTime()
        val process = try {
            builder.start()
        } catch (failure: IOException) {
            throw ProcessExecutionException(
                "Prumo could not start '${request.command.first()}'. Is it installed and on the PATH?",
            )
        }

        // Sem entrada: um comando que pergunte algo termina em vez de ficar preso até o timeout.
        runCatching { process.outputStream.close() }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = reader(process.inputStream, stdout, request.maxOutputBytes)
        val errReader = reader(process.errorStream, stderr, request.maxOutputBytes)

        val finished = process.waitFor(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            destroyTree(process)
        }
        outReader.join(READER_JOIN_MILLIS)
        errReader.join(READER_JOIN_MILLIS)

        val truncated = stdout.length >= request.maxOutputBytes || stderr.length >= request.maxOutputBytes
        return ProcessOutcome(
            exitCode = if (finished) process.exitValue() else null,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = !finished,
            truncated = truncated,
            durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private fun reader(stream: java.io.InputStream, sink: StringBuilder, limit: Int): Thread =
        Thread {
            stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(READ_BUFFER)
                while (true) {
                    val read = try {
                        reader.read(buffer)
                    } catch (_: IOException) {
                        break
                    }
                    if (read <= 0) {
                        break
                    }
                    synchronized(sink) {
                        if (sink.length < limit) {
                            sink.append(buffer, 0, minOf(read, limit - sink.length))
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    /** Matar só o processo deixaria filhos rodando — é como um script de build sobrevive ao timeout. */
    private fun destroyTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private const val READ_BUFFER = 4096
        private const val READER_JOIN_MILLIS = 2_000L
        private const val KILL_GRACE_SECONDS = 5L

        /**
         * O mínimo para um programa encontrar o sistema — e nada além disso.
         *
         * `PATH` entra com os diretórios padrão do sistema operacional, não com o `PATH` da IDE:
         * um `PATH` herdado traz binários de ferramentas de desenvolvimento e ambientes virtuais que
         * o pack não deveria alcançar sem pedir.
         */
        fun minimalEnvironment(probe: EnvironmentProbe): Map<String, String> = buildMap {
            when (probe.operatingSystem) {
                OperatingSystemFamily.WINDOWS -> {
                    val root = probe.variable("SystemRoot") ?: "C:\\Windows"
                    put("SystemRoot", root)
                    put("ComSpec", "$root\\System32\\cmd.exe")
                    put("PATH", "$root\\System32;$root;$root\\System32\\Wbem")
                }

                else -> {
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                }
            }
            put("PRUMO_MCP", "1")
        }
    }
}
