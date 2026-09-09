package io.prumo.mcp.ide

import io.prumo.mcp.documentation.DocumentChunk
import io.prumo.mcp.documentation.IndexedChunk
import io.prumo.mcp.documentation.SourceCoordinate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * O índice de trechos de documentação.
 *
 * Os testes usam vetor **falso e determinístico**: o índice precisa se provar sem depender de modelo
 * nenhum, senão a garantia passa a ser sobre o modelo e não sobre o índice.
 */
class LuceneDocumentIndexTest {

    private val WORKSPACE = "folha"

    /** Todas as fontes que os testes usam, como se o workspace as declarasse. */
    private val TODAS = listOf("mos", "anexo", "confidencial", "manual", "docs")

    @Test
    fun `acha por palavra, e devolve a coordenada para citar`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "regras.pdf", 12, "A rubrica 662 tem teto proprio")))

            val hits = indice.search(WORKSPACE, TODAS, "rubrica", vector = null, maxResults = 5)

            assertEquals(1, hits.size)
            assertEquals("page 12", hits.single().coordinate)
            assertEquals("A rubrica 662 tem teto proprio", hits.single().text)
            assertEquals(false, hits.single().semantic)
        }
    }

    @Test
    fun `acha por proximidade de vetor o que a palavra nao acha`() {
        indice { indice ->
            indice.replaceSource(
                WORKSPACE,
                "mos",
                listOf(
                    trecho("mos", "regras.pdf", 3, "O colaborador recebe o adicional", vetor(1f, 0f)),
                    trecho("mos", "regras.pdf", 9, "Assunto totalmente diferente", vetor(0f, 1f)),
                ),
            )

            val hits = indice.search(WORKSPACE, TODAS, "servidor", vector = floatArrayOf(0.99f, 0.01f), maxResults = 1)

            assertEquals("page 3", hits.single().coordinate)
            assertTrue(hits.single().semantic, "o trecho veio por vetor e não está marcado como tal")
        }
    }

    @Test
    fun `sem vetor a busca continua funcionando por palavra`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "regras.pdf", 1, "pagamento da folha")))

            val hits = indice.search(WORKSPACE, TODAS, "pagamentos", vector = null, maxResults = 5)

            assertEquals(1, hits.size, "o stemming devia achar a flexão")
        }
    }

    @Test
    fun `a mesma consulta devolve sempre a mesma ordem`() {
        indice { indice ->
            indice.replaceSource(
                WORKSPACE,
                "mos",
                (1..5).map { trecho("mos", "regras.pdf", it, "rubrica $it de pagamento", vetor(1f, 0f)) },
            )

            val ordens = (1..5).map { _ ->
                indice.search(WORKSPACE, TODAS, "rubrica", floatArrayOf(1f, 0f), maxResults = 5).map { it.coordinate }
            }

            assertEquals(1, ordens.distinct().size, "ordens diferentes entre chamadas: $ordens")
        }
    }

    @Test
    fun `substituir uma fonte nao toca as outras`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "a.pdf", 1, "rubrica do mos")))
            indice.replaceSource(WORKSPACE, "anexo", listOf(trecho("anexo", "b.pdf", 1, "rubrica do anexo")))

            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "a.pdf", 1, "rubrica do mos, revisada")))

            val hits = indice.search(WORKSPACE, TODAS, "rubrica", vector = null, maxResults = 10)
            assertEquals(2, hits.size)
            assertTrue(hits.any { it.text.contains("revisada") })
            assertTrue(hits.any { it.documentationId == "anexo" })
        }
    }

    @Test
    fun `remover uma fonte tira os trechos dela`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "a.pdf", 1, "rubrica do mos")))
            indice.removeSource(WORKSPACE, "mos")

            assertEquals(0, indice.size())
            assertTrue(indice.search(WORKSPACE, TODAS, "rubrica", vector = null, maxResults = 5).isEmpty())
        }
    }

    @Test
    fun `indice vazio responde sem quebrar`() {
        indice { indice ->
            assertTrue(indice.search(WORKSPACE, TODAS, "rubrica", vector = null, maxResults = 5).isEmpty())
            assertEquals(0, indice.size())
        }
    }

    @Test
    fun `o trecho devolvido diz onde pedir a vizinhanca`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "regras.pdf", 12, "A rubrica 662 tem teto")))

            val hit = indice.search(WORKSPACE, TODAS, "rubrica", vector = null, maxResults = 1).single()

            assertEquals(40, hit.firstLine)
            assertEquals(44, hit.lastLine)
            assertEquals("regras.pdf", hit.path)
        }
    }

    /**
     * O isolamento entre workspaces é a primeira garantia do produto, e o índice é um só para a
     * instalação: dois projetos abertos na mesma IDE não podem se enxergar.
     */
    @Test
    fun `o trecho de um workspace nao aparece no outro`() {
        indice { indice ->
            indice.replaceSource(
                "folha",
                "confidencial",
                listOf(trecho("confidencial", "salarios.xlsx", 1, "tabela de salarios do diretor")),
            )
            indice.replaceSource("outro", "manual", listOf(trecho("manual", "manual.md", 1, "salarios do manual")))

            val doOutro = indice.search("outro", TODAS, "salarios", vector = null, maxResults = 10)

            assertEquals(listOf("manual"), doOutro.map { it.documentationId })
            assertEquals(1, indice.countOf("folha", "confidencial"))
            assertEquals(0, indice.countOf("outro", "confidencial"))
        }
    }

    @Test
    fun `o vetor tambem respeita a fronteira do workspace`() {
        indice { indice ->
            indice.replaceSource(
                "folha",
                "confidencial",
                listOf(trecho("confidencial", "salarios.xlsx", 1, "tabela de salarios", vetor(1f, 0f))),
            )

            val doOutro = indice.search("outro", TODAS, "qualquer", floatArrayOf(1f, 0f), maxResults = 10)

            assertTrue(doOutro.isEmpty(), "a busca vetorial atravessou a fronteira: $doOutro")
        }
    }

    @Test
    fun `a mesma fonte em workspaces diferentes nao se sobrescreve`() {
        indice { indice ->
            indice.replaceSource("folha", "docs", listOf(trecho("docs", "a.md", 1, "regra da folha")))
            indice.replaceSource("outro", "docs", listOf(trecho("docs", "a.md", 1, "regra do outro")))

            assertEquals(1, indice.countOf("folha", "docs"))
            assertEquals(1, indice.countOf("outro", "docs"))
            assertEquals(
                listOf("regra da folha"),
                indice.search("folha", TODAS, "regra", vector = null, maxResults = 5).map { it.text },
            )
        }
    }

    /**
     * O índice vive na memória e guarda o que foi indexado um dia. Quem decide o que ainda vale é o
     * workspace, a cada chamada: fonte desanexada não volta pela busca.
     */
    @Test
    fun `fonte que saiu do workspace nao e devolvida`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "a.pdf", 1, "rubrica do mos")))
            indice.replaceSource(WORKSPACE, "anexo", listOf(trecho("anexo", "b.pdf", 1, "rubrica do anexo")))

            val soComAnexo = indice.search(WORKSPACE, listOf("anexo"), "rubrica", null, 10)

            assertEquals(listOf("anexo"), soComAnexo.map { it.documentationId })
        }
    }

    @Test
    fun `workspace sem fonte declarada nao devolve nada`() {
        indice { indice ->
            indice.replaceSource(WORKSPACE, "mos", listOf(trecho("mos", "a.pdf", 1, "rubrica do mos")))

            assertTrue(indice.search(WORKSPACE, emptyList(), "rubrica", null, 10).isEmpty())
        }
    }

    @Test
    fun `o vetor tambem respeita a fonte declarada`() {
        indice { indice ->
            indice.replaceSource(
                WORKSPACE,
                "mos",
                listOf(trecho("mos", "a.pdf", 1, "rubrica do mos", vetor(1f, 0f))),
            )

            assertTrue(indice.search(WORKSPACE, listOf("anexo"), "qualquer", floatArrayOf(1f, 0f), 10).isEmpty())
        }
    }

    private fun indice(bloco: (LuceneDocumentIndex) -> Unit) = LuceneDocumentIndex().use(bloco)

    private fun vetor(vararg valores: Float) = valores

    private fun trecho(
        fonte: String,
        caminho: String,
        pagina: Int,
        texto: String,
        vetor: FloatArray? = null,
    ) = IndexedChunk(
        documentationId = fonte,
        path = caminho,
        chunk = DocumentChunk(SourceCoordinate.Page(pagina), texto, firstLine = 40, lastLine = 44),
        vector = vetor,
    )
}
