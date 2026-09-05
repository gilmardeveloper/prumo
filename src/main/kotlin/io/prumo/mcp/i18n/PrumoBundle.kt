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
 * (`PrumoBundle_pt_BR.properties`). Por padrão quem escolhe é a IDE: o `DynamicBundle` segue o
 * idioma configurado em *Appearance & Behavior · System Settings · Language & Region*, e sem
 * tradução disponível cai no inglês. Quem preferir divergir da IDE ajusta a preferência do Prumo,
 * e então o locale passa a ser resolvido aqui, explicitamente.
 *
 * **A superfície MCP não passa por aqui.** Nome de tool, descrição e erro devolvido ao cliente são
 * contrato lido por uma IA: se mudassem com o idioma da máquina, a mesma tool responderia diferente
 * em cada lugar. Um teste falha se alguma classe de `toolsets/` referenciar este bundle.
 */
object PrumoBundle : DynamicBundle(PrumoBundle::class.java, BUNDLE) {

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg parameters: Any): String {
        val locale = PrumoLanguageSetting.shared.current().locale()
            ?: return getMessage(key, *parameters)
        return AbstractBundle.message(localized(locale), key, *parameters)
    }

    /**
     * Carrega o bundle de um idioma específico, ignorando o idioma da IDE.
     *
     * Não passa pela plataforma de propósito: `getResourceBundleLocalized` faria exatamente isto,
     * mas é `@ApiStatus.Internal` e reprova na verificação do plugin, e a sobrecarga pública de
     * `getResourceBundle` resolve pelo idioma da IDE, não pelo locale pedido. O `ResourceBundle` do
     * JDK basta porque as traduções são arquivos que o próprio Prumo embarca.
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
