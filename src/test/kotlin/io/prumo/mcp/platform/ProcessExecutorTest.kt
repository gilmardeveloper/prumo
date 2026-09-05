package io.prumo.mcp.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Confinamento verificado contra o sistema operacional corrente.
 *
 * Os comandos são escolhidos pelo SO em execução, para a suíte provar o mesmo em Windows e Linux.
 */
@Tag("security")
class ProcessExecutorTest {

    private val executor = ProcessExecutor()
    private val windows = OperatingSystemFamily.fromName(System.getProperty("os.name")) == OperatingSystemFamily.WINDOWS

    private fun shell(command: String): List<String> =
        if (windows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)

    @Test
    fun `o processo roda no diretorio que o Prumo determinou`(@TempDir root: Path) {
        val outcome = executor.run(
            ProcessRequest(shell(if (windows) "cd" else "pwd"), root, timeoutSeconds = 15),
        )

        assertEquals(0, outcome.exitCode)
        assertTrue(
            outcome.stdout.trim().lowercase().contains(root.toRealPath().toString().lowercase()),
            outcome.stdout,
        )
    }

    @Test
    fun `o ambiente da IDE nao chega ao script`(@TempDir root: Path) {
        val outcome = executor.run(
            ProcessRequest(shell(if (windows) "set" else "env"), root, timeoutSeconds = 15),
        )

        val environment = outcome.stdout.lowercase()
        assertTrue(environment.contains("prumo_mcp"), "a marca do Prumo precisa estar la")
        // JAVA_HOME existe no processo que roda esta suite; se vazasse, apareceria aqui.
        assertFalse(environment.contains("java_home"), "variavel da IDE nao pode vazar para o script")
        assertFalse(environment.contains("gradle_"), "variavel de build nao pode vazar")
    }

    @Test
    fun `variavel declarada pelo pack chega ao script`(@TempDir root: Path) {
        val outcome = executor.run(
            ProcessRequest(
                command = shell(if (windows) "echo %PRUMO_PACK_ID%" else "echo \$PRUMO_PACK_ID"),
                workingDirectory = root,
                timeoutSeconds = 15,
                environment = mapOf("PRUMO_PACK_ID" to "folha-tools"),
            ),
        )

        assertEquals("folha-tools", outcome.stdout.trim())
    }

    @Test
    fun `estourar o tempo mata o processo`(@TempDir root: Path) {
        val outcome = executor.run(
            ProcessRequest(
                command = shell(if (windows) "ping -n 20 127.0.0.1 > nul" else "sleep 20"),
                workingDirectory = root,
                timeoutSeconds = 2,
            ),
        )

        assertTrue(outcome.timedOut)
        assertNull(outcome.exitCode, "processo morto nao tem codigo de saida")
        assertTrue(outcome.durationMillis < 15_000, "precisa morrer perto do timeout, nao esperar o fim")
    }

    @Test
    fun `saida grande demais e cortada`(@TempDir root: Path) {
        val comando = if (windows) {
            "for /l %i in (1,1,5000) do @echo linha-de-saida-bem-longa-para-encher-o-buffer"
        } else {
            "for i in $(seq 1 5000); do echo linha-de-saida-bem-longa-para-encher-o-buffer; done"
        }

        val outcome = executor.run(
            ProcessRequest(shell(comando), root, timeoutSeconds = 30, maxOutputBytes = 2_000),
        )

        assertTrue(outcome.truncated)
        assertTrue(outcome.stdout.length <= 2_000)
    }

    @Test
    fun `codigo de saida de falha chega inteiro`(@TempDir root: Path) {
        val outcome = executor.run(
            ProcessRequest(shell("exit 3"), root, timeoutSeconds = 15),
        )

        assertEquals(3, outcome.exitCode)
        assertFalse(outcome.succeeded)
    }

    @Test
    fun `comando inexistente falha com mensagem que diz o que houve`(@TempDir root: Path) {
        val failure = assertThrows<ProcessExecutionException> {
            executor.run(ProcessRequest(listOf("prumo-comando-que-nao-existe"), root, timeoutSeconds = 5))
        }

        assertTrue(failure.message.orEmpty().contains("could not start"))
    }

    @Test
    fun `diretorio de trabalho inexistente e recusado antes de executar`(@TempDir root: Path) {
        assertThrows<ProcessExecutionException> {
            executor.run(ProcessRequest(shell("echo x"), root.resolve("nao-existe"), timeoutSeconds = 5))
        }
    }

    @Test
    fun `o ambiente minimo tem so o necessario`() {
        val windowsEnvironment = ProcessExecutor.minimalEnvironment(probe(OperatingSystemFamily.WINDOWS))
        val linuxEnvironment = ProcessExecutor.minimalEnvironment(probe(OperatingSystemFamily.LINUX))

        assertEquals(setOf("SystemRoot", "ComSpec", "PATH", "PRUMO_MCP"), windowsEnvironment.keys)
        assertEquals(setOf("PATH", "PRUMO_MCP"), linuxEnvironment.keys)
        assertFalse(linuxEnvironment.getValue("PATH").contains(System.getProperty("user.home")))
    }

    private fun probe(family: OperatingSystemFamily) = object : EnvironmentProbe {
        override fun variable(name: String): String? = null

        override fun systemProperty(name: String): String? = null

        override val operatingSystem: OperatingSystemFamily = family
    }
}
