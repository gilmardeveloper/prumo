package io.prumo.mcp.workspace.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Lê o papel de um repositório aceitando valores que o enum não tem mais.
 *
 * `ignoreUnknownKeys` cobre chave desconhecida, não valor de enum desconhecido: sem este
 * serializador, um papel retirado torna ilegível o arquivo inteiro do workspace, e com ele os
 * repositórios, a documentação, os bancos e as políticas.
 *
 * `TARGET` foi consolidado em [RepositoryRole.PRIMARY]. Qualquer outro valor desconhecido vira
 * [RepositoryRole.REFERENCE], que é a leitura mais conservadora — o papel não concede acesso algum;
 * quem concede é o `accessMode`, que vem do mesmo arquivo e não passa por aqui.
 */
object RepositoryRoleSerializer : KSerializer<RepositoryRole> {

    private const val CONSOLIDATED_INTO_PRIMARY = "TARGET"

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.prumo.mcp.workspace.domain.RepositoryRole", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RepositoryRole) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): RepositoryRole {
        val raw = decoder.decodeString()
        RepositoryRole.entries.firstOrNull { it.name == raw }?.let { return it }
        return if (raw == CONSOLIDATED_INTO_PRIMARY) RepositoryRole.PRIMARY else RepositoryRole.REFERENCE
    }
}
