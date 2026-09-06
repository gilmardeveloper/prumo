package io.prumo.mcp.settings

import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * A preferência que permite ao Prumo divergir do idioma da IDE.
 *
 * O que importa aqui é o comportamento quando não há preferência e quando o arquivo não presta:
 * nos dois casos a interface precisa continuar existindo, no idioma da IDE.
 */
class PrumoLanguageSettingTest {

    @Test
    fun `sem preferencia gravada o idioma acompanha a IDE`(@TempDir root: Path) {
        assertEquals(PrumoLanguage.SYSTEM, setting(root).current())
        assertNull(PrumoLanguage.SYSTEM.locale())
    }

    @Test
    fun `a preferencia sobrevive a uma nova leitura do disco`(@TempDir root: Path) {
        setting(root).update(PrumoLanguage.BRAZILIAN_PORTUGUESE)

        assertEquals(PrumoLanguage.BRAZILIAN_PORTUGUESE, setting(root).current())
    }

    @Test
    fun `cada idioma resolve o locale correspondente`() {
        assertEquals(Locale.ENGLISH, PrumoLanguage.ENGLISH.locale())
        assertEquals("pt", PrumoLanguage.BRAZILIAN_PORTUGUESE.locale()?.language)
        assertEquals("BR", PrumoLanguage.BRAZILIAN_PORTUGUESE.locale()?.country)
    }

    @Test
    fun `arquivo corrompido nao derruba a interface`(@TempDir root: Path) {
        val storage = storage(root)
        Files.createDirectories(storage.settingsRoot())
        Files.writeString(storage.settingsRoot().resolve("language.json"), "{", StandardCharsets.UTF_8)

        assertEquals(PrumoLanguage.SYSTEM, setting(root).current())
    }

    @Test
    fun `ambiente sem diretorio do usuario nao derruba o texto da interface`() {
        val language = PrumoLanguageSetting.languageOf {
            error("Prumo MCP could not determine the user home directory.")
        }

        assertEquals(PrumoLanguage.SYSTEM, language)
    }

    @Test
    fun `com a area de configuracao no lugar, a preferencia gravada e respeitada`(@TempDir root: Path) {
        setting(root).update(PrumoLanguage.ENGLISH)

        assertEquals(PrumoLanguage.ENGLISH, PrumoLanguageSetting.languageOf { setting(root) })
    }

    @Test
    fun `a preferencia e gravada fora de qualquer repositorio, na area de configuracao`(@TempDir root: Path) {
        setting(root).update(PrumoLanguage.ENGLISH)

        val file = storage(root).settingsRoot().resolve("language.json")
        assertEquals(true, Files.exists(file))
        assertEquals(true, Files.readString(file, StandardCharsets.UTF_8).contains("ENGLISH"))
    }

    private fun setting(root: Path) = PrumoLanguageSetting(storage(root))

    private fun storage(root: Path) = FileSystemStorageProvider(
        PrumoDirectories(
            config = root.resolve("config"),
            data = root.resolve("data"),
            cache = root.resolve("cache"),
        ),
    )
}
