package io.prumo.mcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * A versão do plugin tem uma fonte só: `gradle.properties`.
 *
 * Uma constante escrita à mão no código envelhece na primeira release e passa a mentir para quem
 * tenta descobrir se a correção chegou à máquina. Já aconteceu: `prumo_diagnostics` respondia
 * `0.1.0` com a `0.1.0-rc.4` instalada, e por pouco não invalidou uma sessão inteira de validação.
 */
class PluginVersionTest {

    @Test
    fun `nenhum fonte Kotlin repete a versao declarada no build`() {
        val versao = Properties().apply {
            Files.newBufferedReader(Path.of("gradle.properties"), StandardCharsets.UTF_8).use { load(it) }
        }.getProperty("pluginVersion")

        assertTrue(!versao.isNullOrBlank(), "gradle.properties precisa declarar pluginVersion")

        val infratores = fontes()
            .filter { (_, code) -> code.contains("\"$versao\"") }
            .map { (path, _) -> path }

        assertTrue(
            infratores.isEmpty(),
            "versão '$versao' cravada no código; leia do descritor instalado: $infratores",
        )
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun fontes(): List<Pair<String, String>> =
        Path.of("src/main/kotlin").walk()
            .filter { it.extension == "kt" }
            .map { it.fileName.toString() to it.readText() }
            .toList()
}
