package io.prumo.mcp.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Varre o código de produção para sustentar a separação entre o núcleo determinístico e a IDE.
 *
 * O núcleo é testado sem uma IDE no ar, e isso só continua verdadeiro enquanto nenhum tipo da
 * plataforma atravessa a fronteira. A regra vale para o pacote inteiro: um pacote novo nasce dentro
 * do núcleo, e passa a ser borda apenas quando alguém o declara aqui.
 */
class CoreIndependenceTest {

    @Test
    fun `nenhum pacote do nucleo menciona um tipo da IDE`() {
        val offenders = sources()
            .filterNot { it.layer in BOUNDARY }
            .filter { source -> IDE_PACKAGES.any { source.text.contains(it) } }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "o núcleo passou a depender da IDE em: ${offenders.joinToString("\n", "\n")}",
        )
    }

    @Test
    fun `o PSI e o Git da IDE vivem so na camada ide`() {
        val offenders = sources()
            .filterNot { it.layer == "ide" }
            .filter { source -> IDE_ONLY_TYPES.any { source.text.contains(it) } }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "projeto, PSI ou Git da IDE fora de ide/: ${offenders.joinToString("\n", "\n")}",
        )
    }

    @Test
    fun `a varredura alcanca os dois lados da fronteira`() {
        val scanned = sources()

        assertTrue(
            scanned.any { it.layer !in BOUNDARY },
            "nenhum arquivo de núcleo foi varrido: a regra não estaria valendo para ninguém",
        )
        assertTrue(
            scanned.any { it.layer in BOUNDARY && IDE_PACKAGES.any { ide -> it.text.contains(ide) } },
            "nenhum arquivo de borda cita a IDE: a varredura não está lendo o código de produção",
        )
    }

    @Test
    fun `toda camada de borda declarada existe`() {
        val packages = ROOT.listDirectoryEntries().filter { it.isDirectory() }.map { it.fileName.toString() }

        assertEquals(
            emptySet<String>(),
            BOUNDARY - packages.toSet(),
            "camada de borda declarada e inexistente: a lista protegeria um pacote que não existe mais",
        )
    }

    private data class Source(val path: String, val layer: String, val text: String)

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    private fun sources(): List<Source> = ROOT.walk()
        .filter { it.isRegularFile() && it.extension == "kt" }
        .map { file ->
            Source(
                path = ROOT.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                layer = ROOT.relativize(file).first().toString(),
                text = file.readText(),
            )
        }
        .toList()

    private companion object {
        val ROOT: Path = Path.of("src/main/kotlin/io/prumo/mcp")

        /** Pacotes autorizados a falar com a plataforma. Todo o resto é núcleo. */
        val BOUNDARY = setOf("ide", "ui", "toolsets", "settings", "credential", "i18n")

        val IDE_PACKAGES = listOf("com.intellij", "com.jetbrains", "git4idea")

        val IDE_ONLY_TYPES = listOf("com.intellij.psi", "git4idea")
    }
}
