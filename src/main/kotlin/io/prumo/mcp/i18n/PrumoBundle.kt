package io.prumo.mcp.i18n

import com.intellij.AbstractBundle
import com.intellij.DynamicBundle
import io.prumo.mcp.settings.PrumoLanguageSetting
import org.jetbrains.annotations.PropertyKey
import java.util.Locale
import java.util.ResourceBundle

private const val BUNDLE = "messages.PrumoBundle"

/**
 * Todo texto que um humano lê na interface do Prumo.
 *
 * O inglês é a base (`PrumoBundle.properties`) e o português vem ao lado
 * (`PrumoBundle_pt_BR.properties`). Por padrão o idioma é o da IDE; havendo preferência do Prumo,
 * o locale é resolvido aqui.
 *
 * A superfície MCP não passa por aqui: nome de tool, descrição e erro devolvido ao cliente ficam
 * sempre em inglês.
 */
object PrumoBundle : DynamicBundle(PrumoBundle::class.java, BUNDLE) {

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg parameters: Any): String {
        val locale = PrumoLanguageSetting.currentLanguage().locale()
            ?: return getMessage(key, *parameters)
        return AbstractBundle.message(localized(locale), key, *parameters)
    }

    /**
     * Carrega o bundle de um idioma específico, ignorando o idioma da IDE.
     *
     * Usa o `ResourceBundle` do JDK: as sobrecargas da plataforma ou são API interna ou resolvem pelo
     * idioma da IDE em vez do locale pedido.
     */
    internal fun localized(locale: Locale): ResourceBundle =
        ResourceBundle.getBundle(BUNDLE, locale, PrumoBundle::class.java.classLoader, EXPLICIT_LOCALE)

    /**
     * Sem isto, pedir inglês numa máquina configurada em português devolveria português: o
     * `ResourceBundle` recorre ao idioma padrão da máquina quando não encontra o que foi pedido.
     */
    private val EXPLICIT_LOCALE = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null
    }
}
