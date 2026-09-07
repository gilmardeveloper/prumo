package io.prumo.mcp.knowledge

import kotlinx.serialization.Serializable

/** De que família de fonte um registro foi destilado. */
@Serializable
enum class SourceKind {
    DOCUMENTATION,
    REPOSITORY,
}

/**
 * O estado de uma fonte no instante em que o conhecimento foi destilado dela.
 *
 * Carrega três medidas em vez de uma porque elas falham de formas diferentes: o tamanho pega a
 * maioria das edições sem custo, a data pega a reescrita que não muda o tamanho, e o resumo pega o
 * resto. Divergir em qualquer uma basta para o registro ser considerado velho.
 */
@Serializable
data class SourceStamp(
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val sha256: String,
)

/**
 * De onde um registro veio, e em que estado a fonte estava.
 *
 * A coordenada — caminho e intervalo de linhas — é informativa: serve para o leitor voltar à fonte,
 * não para o veredicto. Quem decide o frescor é o carimbo, e ele é do arquivo inteiro.
 */
@Serializable
data class Provenance(
    val sourceKind: SourceKind,
    val sourceId: String,
    val path: String? = null,
    val firstLine: Int? = null,
    val lastLine: Int? = null,
    val stamp: SourceStamp,
)

/** O que o Prumo responde sobre um registro, ao lado do conteúdo, sempre. */
enum class Freshness {
    /** A fonte está como estava quando o registro nasceu. */
    FRESH,

    /** A fonte mudou. O conteúdo continua legível, e pode estar errado. */
    STALE,

    /** A fonte não existe mais ao alcance do workspace. */
    ORPHAN,
}

/**
 * Compara o carimbo gravado com o estado atual da fonte.
 *
 * Fail-closed por desenho: só devolve [Freshness.FRESH] quando as três medidas coincidem. Um falso
 * `STALE` custa uma redestilação; um falso `FRESH` entrega conhecimento errado como se fosse bom.
 *
 * @param current o carimbo recalculado agora, ou `null` quando a fonte não foi encontrada.
 */
fun freshnessOf(recorded: SourceStamp, current: SourceStamp?): Freshness = when {
    current == null -> Freshness.ORPHAN
    current == recorded -> Freshness.FRESH
    else -> Freshness.STALE
}
