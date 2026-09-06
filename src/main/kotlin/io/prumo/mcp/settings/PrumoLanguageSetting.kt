package io.prumo.mcp.settings

import com.intellij.openapi.diagnostic.Logger
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.storage.JsonStore
import io.prumo.mcp.storage.LocalStorageProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.io.IOException
import java.nio.file.Path
import java.util.Locale

/** Idioma em que o Prumo fala com o humano. */
@Serializable
enum class PrumoLanguage(val labelKey: String) {

    /** Acompanha a IDE. É o padrão. */
    SYSTEM("settings.language.system"),
    ENGLISH("settings.language.english"),
    BRAZILIAN_PORTUGUESE("settings.language.brazilianPortuguese"),
    ;

    /** `null` significa "o que a IDE estiver usando" — não há locale a impor. */
    fun locale(): Locale? = when (this) {
        SYSTEM -> null
        ENGLISH -> Locale.ENGLISH
        BRAZILIAN_PORTUGUESE -> BRAZIL
    }

    private companion object {
        val BRAZIL: Locale = Locale.forLanguageTag("pt-BR")
    }
}

/**
 * Preferência de idioma do Prumo, guardada com as demais configurações do produto.
 *
 * Fica na área do sistema operacional. O valor é mantido em memória e só volta ao disco quando
 * alguém o altera.
 */
class PrumoLanguageSetting(
    private val storage: LocalStorageProvider,
    private val json: JsonStore = JsonStore(),
) {

    @Volatile
    private var cached: PrumoLanguage? = null

    fun current(): PrumoLanguage = cached ?: read().also { cached = it }

    fun update(language: PrumoLanguage) {
        json.write(file(), serializer<LanguageDocument>(), LanguageDocument(language))
        cached = language
    }

    /**
     * Lê a preferência do disco.
     *
     * Configuração ilegível registra o motivo e devolve [PrumoLanguage.SYSTEM].
     */
    private fun read(): PrumoLanguage =
        try {
            json.read(file(), serializer<LanguageDocument>())?.language ?: PrumoLanguage.SYSTEM
        } catch (cause: IOException) {
            LOG.warn("Prumo MCP could not read the language preference at ${file()}; falling back to the IDE language.", cause)
            PrumoLanguage.SYSTEM
        }

    private fun file(): Path = storage.settingsRoot().resolve(FILE)

    companion object {
        private const val FILE = "language.json"
        private val LOG = Logger.getInstance(PrumoLanguageSetting::class.java)

        /** A instância que a interface usa. O construtor fica aberto para o teste apontar outro disco. */
        val shared: PrumoLanguageSetting by lazy {
            PrumoLanguageSetting(FileSystemStorageProvider.forCurrentSystem())
        }

        /** O idioma em que a interface fala. */
        fun currentLanguage(): PrumoLanguage = languageOf { shared }

        /**
         * Resolve o idioma tolerando ambiente sem área de configuração alcançável.
         *
         * Máquina em que o diretório do usuário não se resolve registra o motivo e recebe
         * [PrumoLanguage.SYSTEM]: a interface continua existindo, no idioma da IDE.
         */
        internal fun languageOf(source: () -> PrumoLanguageSetting): PrumoLanguage =
            try {
                source().current()
            } catch (cause: RuntimeException) {
                LOG.warn("Prumo MCP could not resolve the language preference; falling back to the IDE language.", cause)
                PrumoLanguage.SYSTEM
            }
    }
}

@Serializable
private data class LanguageDocument(
    val language: PrumoLanguage = PrumoLanguage.SYSTEM,
)
