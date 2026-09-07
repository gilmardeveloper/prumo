package io.prumo.mcp.knowledge

import io.prumo.mcp.ide.MvStoreKnowledgeStore
import io.prumo.mcp.storage.LocalStorageProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O armazenamento é a única parte do produto que precisa sobreviver a duas escritas ao mesmo tempo:
 * a base é escrita por clientes de IA, e nada garante que só um esteja conectado.
 */
class KnowledgeStoreTest {

    @TempDir
    lateinit var root: Path

    private lateinit var store: MvStoreKnowledgeStore

    private val workspace = "folha-esocial"

    @BeforeEach
    fun abrir() {
        store = MvStoreKnowledgeStore(TempStorage(root))
    }

    @AfterEach
    fun fechar() {
        store.close()
    }

    @Test
    fun `o que foi gravado volta inteiro`() {
        store.put(workspace, record("s-1210-prazo"))

        val lido = store.get(workspace, "s-1210-prazo")

        assertNotNull(lido)
        assertEquals("s-1210-prazo", lido!!.id)
        assertEquals("eventos-esocial", lido.provenance.sourceId)
        assertEquals("claude-code/2.1", lido.author)
        assertEquals(listOf("esocial", "prazo"), lido.tags)
    }

    @Test
    fun `identificador ausente devolve nulo, nao erro`() {
        assertNull(store.get(workspace, "nunca-gravado"))
    }

    @Test
    fun `gravar de novo substitui, e nao duplica`() {
        store.put(workspace, record("s-1210-prazo", title = "primeira"))
        store.put(workspace, record("s-1210-prazo", title = "segunda"))

        assertEquals(1, store.list(workspace).size)
        assertEquals("segunda", store.get(workspace, "s-1210-prazo")?.title)
    }

    @Test
    fun `remover devolve se havia algo para remover`() {
        store.put(workspace, record("s-1210-prazo"))

        assertTrue(store.remove(workspace, "s-1210-prazo"))
        assertFalse(store.remove(workspace, "s-1210-prazo"))
        assertNull(store.get(workspace, "s-1210-prazo"))
    }

    @Test
    fun `a listagem sai em ordem estavel de identificador`() {
        listOf("c-item", "a-item", "b-item").forEach { store.put(workspace, record(it)) }

        assertEquals(listOf("a-item", "b-item", "c-item"), store.list(workspace).map { it.id })
    }

    @Test
    fun `recorte por etiqueta ignora maiusculas e nao casa por fragmento`() {
        store.put(workspace, record("um", tags = listOf("eSocial", "prazo")))
        store.put(workspace, record("dois", tags = listOf("folha")))

        assertEquals(listOf("um"), store.byTag(workspace, "esocial").map { it.id })
        assertTrue(store.byTag(workspace, "social").isEmpty(), "casou por fragmento")
    }

    @Test
    fun `recorte por fonte responde o que ja se sabe sobre um arquivo`() {
        store.put(workspace, record("um", sourceId = "eventos-esocial"))
        store.put(workspace, record("dois", sourceId = "eventos-esocial"))
        store.put(workspace, record("tres", sourceId = "outra-fonte"))

        assertEquals(listOf("dois", "um"), store.bySource(workspace, "eventos-esocial").map { it.id })
    }

    /** O isolamento nasce do endereçamento, como no resto do produto. */
    @Test
    fun `um workspace nao enxerga o conhecimento de outro`() {
        store.put(workspace, record("s-1210-prazo"))
        store.put("outro-workspace", record("s-1210-prazo", title = "de outro"))

        assertEquals("titulo", store.get(workspace, "s-1210-prazo")?.title)
        assertEquals("de outro", store.get("outro-workspace", "s-1210-prazo")?.title)
        assertEquals(1, store.list(workspace).size)
    }

    @Test
    fun `formato desconhecido e recusado na leitura, com instrucao de redestilar`() {
        store.put(workspace, record("do-futuro").copy(schemaVersion = 99))

        val falha = assertThrows(UnsupportedSchemaException::class.java) {
            store.get(workspace, "do-futuro")
        }

        assertEquals(99, falha.version)
        assertTrue(falha.message!!.contains("Distil it again"))
    }

    /**
     * O teste que o produto nunca teve. No armazenamento JSON, duas escritas simultâneas no mesmo
     * caminho se resolvem por último-a-gravar-vence, em silêncio. Aqui as duas precisam sobreviver.
     */
    @Test
    fun `duas escritas simultaneas nao se perdem`() {
        val quantidade = 40
        val largada = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        try {
            val tarefas = (1..quantidade).map { indice ->
                pool.submit {
                    largada.await()
                    store.put(workspace, record("registro-%03d".format(indice)))
                }
            }
            largada.countDown()
            tarefas.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(quantidade, store.list(workspace).size, "escrita concorrente perdeu registro")
    }

    /**
     * A operação composta é o que justifica a transação: sozinha, uma escrita de chave única já
     * seria segura, porque o mapa do MVStore é concorrente.
     */
    @Test
    fun `redestilar uma fonte substitui tudo o que vinha dela, e nao toca as outras`() {
        store.put(workspace, record("velho-um", sourceId = "eventos-esocial"))
        store.put(workspace, record("velho-dois", sourceId = "eventos-esocial"))
        store.put(workspace, record("de-outra", sourceId = "outra-fonte"))

        store.replaceFromSource(
            workspace,
            "eventos-esocial",
            listOf(record("novo-um", sourceId = "eventos-esocial")),
        )

        assertEquals(listOf("de-outra", "novo-um"), store.list(workspace).map { it.id })
    }

    /**
     * O caminho de exceção. Sem rollback, a remoção dos registros antigos já teria acontecido quando
     * a falha chegasse — e a base ficaria sem o que tinha e sem o que viria.
     */
    @Test
    fun `redestilacao que falha no meio nao remove o que ja existia`() {
        store.put(workspace, record("velho-um", sourceId = "eventos-esocial"))
        store.put(workspace, record("velho-dois", sourceId = "eventos-esocial"))
        store.put(workspace, record("corrompido", sourceId = "eventos-esocial").copy(schemaVersion = 99))

        assertThrows(UnsupportedSchemaException::class.java) {
            store.replaceFromSource(
                workspace,
                "eventos-esocial",
                listOf(record("novo-um", sourceId = "eventos-esocial")),
            )
        }

        assertNotNull(store.get(workspace, "velho-um"), "a redestilação apagou o que existia antes")
        assertNotNull(store.get(workspace, "velho-dois"))
        assertNull(store.get(workspace, "novo-um"), "gravou o novo apesar da falha")
    }

    private fun record(
        id: String,
        title: String = "titulo",
        tags: List<String> = listOf("esocial", "prazo"),
        sourceId: String = "eventos-esocial",
    ) = KnowledgeRecord(
        schemaVersion = KnowledgeRecord.CURRENT_SCHEMA_VERSION,
        id = id,
        title = title,
        body = "O S-1210 tem prazo até o dia 15 do mês seguinte.",
        tags = tags,
        provenance = Provenance(
            sourceKind = SourceKind.DOCUMENTATION,
            sourceId = sourceId,
            path = "S-1210.md",
            firstLine = 40,
            lastLine = 96,
            stamp = SourceStamp(65_557, 1_755_500_000_000, "sha256:abc123"),
        ),
        author = "claude-code/2.1",
        createdAt = "2026-09-07T12:00:00Z",
        updatedAt = "2026-09-07T12:00:00Z",
    )

    /** Storage de teste: mesma forma do provedor real, apontando para o diretório temporário. */
    private class TempStorage(private val root: Path) : LocalStorageProvider {
        override fun workspacesRoot(): Path = root.resolve("workspaces")
        override fun workspaceRoot(workspaceId: String): Path = workspacesRoot().resolve(workspaceId)
        override fun settingsRoot(): Path = root.resolve("settings")
        override fun clientsRoot(): Path = root.resolve("clients")
        override fun runtimeRoot(): Path = root.resolve("runtime")
        override fun cacheRoot(): Path = root.resolve("cache")
        override fun prepare() = Unit
    }
}
