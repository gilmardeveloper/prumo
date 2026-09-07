package io.prumo.mcp.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectionRecordTest {

    private val unusedImport = record("UnusedImport", group = "Imports", severity = "WARNING")

    private val typo = record(
        "SpellCheckingInspection",
        group = "Proofreading",
        severity = "TYPO",
        language = null,
    )

    private val deprecation = record(
        "Deprecation",
        group = "Code maturity",
        severity = "WEAK WARNING",
        enabledByDefault = false,
    )

    private val nullability = record("NullableProblems", group = "Code maturity", severity = "WARNING")

    private val catalog = listOf(unusedImport, typo, deprecation, nullability)

    @Test
    fun `ordena por grupo e desempata pelo identificador estavel`() {
        val ordered = catalog.sortedForCatalog().map { it.shortName }

        assertEquals(
            listOf("Deprecation", "NullableProblems", "UnusedImport", "SpellCheckingInspection"),
            ordered,
        )
    }

    @Test
    fun `a ordem nao depende da ordem de chegada`() {
        assertEquals(
            catalog.sortedForCatalog(),
            catalog.reversed().sortedForCatalog(),
        )
    }

    @Test
    fun `filtra por severidade fora do vocabulario conhecido`() {
        val found = catalog.matching(InspectionFilter(severity = "TYPO"))

        assertEquals(listOf("SpellCheckingInspection"), found.map { it.shortName })
    }

    @Test
    fun `a severidade casa sem depender de maiusculas mas nao por fragmento`() {
        assertEquals(2, catalog.matching(InspectionFilter(severity = "warning")).size)
        assertTrue(catalog.matching(InspectionFilter(severity = "WARN")).isEmpty())
    }

    @Test
    fun `inspecao sem linguagem declarada fica de fora do filtro por linguagem`() {
        val found = catalog.matching(InspectionFilter(language = "kotlin"))

        assertEquals(listOf("UnusedImport", "Deprecation", "NullableProblems"), found.map { it.shortName })
    }

    @Test
    fun `filtra pelo que a declaracao diz sobre estar ligada de fabrica`() {
        assertEquals(
            listOf("Deprecation"),
            catalog.matching(InspectionFilter(enabledByDefault = false)).map { it.shortName },
        )
        assertEquals(3, catalog.matching(InspectionFilter(enabledByDefault = true)).size)
    }

    @Test
    fun `criterio vazio devolve o catalogo inteiro na ordem de chegada`() {
        assertEquals(catalog, catalog.matching(InspectionFilter()))
    }

    @Test
    fun `criterios acumulam em vez de se substituirem`() {
        val found = catalog.matching(InspectionFilter(group = "Code maturity", severity = "WARNING"))

        assertEquals(listOf("NullableProblems"), found.map { it.shortName })
    }

    @Test
    fun `catalogo vazio nao quebra a ordenacao nem o filtro`() {
        val empty = emptyList<InspectionRecord>()

        assertTrue(empty.sortedForCatalog().isEmpty())
        assertTrue(empty.matching(InspectionFilter(severity = "WARNING")).isEmpty())
    }

    private fun record(
        shortName: String,
        group: String,
        severity: String,
        language: String? = "kotlin",
        enabledByDefault: Boolean = true,
    ) = InspectionRecord(
        shortName = shortName,
        displayName = "$shortName display name",
        group = group,
        severity = severity,
        language = language,
        enabledByDefault = enabledByDefault,
    )
}
