package io.prumo.mcp.ui

import io.prumo.mcp.documentation.DocumentAuthority
import io.prumo.mcp.documentation.DocumentationKind
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.WorkspaceType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Rótulo prometido em código precisa existir nos dois idiomas.
 *
 * O `when` exaustivo de `EnumLabels` obriga a declarar a chave, não a traduzi-la; a chave sem
 * entrada no bundle só apareceria como falha na tela do usuário.
 */
class EnumLabelsTest {

    private val base = load("src/main/resources/messages/PrumoBundle.properties")
    private val brazilian = load("src/main/resources/messages/PrumoBundle_pt_BR.properties")

    @Test
    fun `todo valor exibido tem rotulo nos dois idiomas`() {
        val ausentes = chaves().flatMap { key ->
            listOf(
                "inglês: $key".takeIf { !base.containsKey(key) },
                "português: $key".takeIf { !brazilian.containsKey(key) },
            ).filterNotNull()
        }

        assertTrue(ausentes.isEmpty(), "rótulo prometido em EnumLabels e ausente no bundle: $ausentes")
    }

    @Test
    fun `nenhum rotulo repete o identificador do enum`() {
        val crus = chaves().filter { key ->
            val texto = brazilian.getProperty(key).orEmpty()
            texto.isBlank() || texto == texto.uppercase() && texto.contains('_')
        }

        assertTrue(crus.isEmpty(), "rótulo em português que ainda é o identificador do enum: $crus")
    }

    private fun chaves(): List<String> =
        WorkspaceType.entries.map { it.labelKey } +
            RepositoryRole.entries.map { it.labelKey } +
            AccessMode.entries.map { it.labelKey } +
            DocumentationKind.entries.map { it.labelKey } +
            DocumentAuthority.entries.map { it.labelKey }

    private fun load(path: String): Properties = Properties().apply {
        Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8).use { load(it) }
    }
}
