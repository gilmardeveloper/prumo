package io.prumo.mcp.i18n

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.PrumoBundle"

/**
 * Todo texto que um humano lê na interface do Prumo.
 *
 * O inglês é a base (`PrumoBundle.properties`) e o português vem ao lado
 * (`PrumoBundle_pt_BR.properties`). Quem escolhe é a IDE: o `DynamicBundle` segue o idioma
 * configurado em *Appearance & Behavior · System Settings · Language & Region*, e sem tradução
 * disponível cai no inglês.
 *
 * **A superfície MCP não passa por aqui.** Nome de tool, descrição e erro devolvido ao cliente são
 * contrato lido por uma IA: se mudassem com o idioma da máquina, a mesma tool responderia diferente
 * em cada lugar. Um teste falha se alguma classe de `toolsets/` referenciar este bundle.
 */
object PrumoBundle : DynamicBundle(PrumoBundle::class.java, BUNDLE) {

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg parameters: Any): String =
        getMessage(key, *parameters)
}
