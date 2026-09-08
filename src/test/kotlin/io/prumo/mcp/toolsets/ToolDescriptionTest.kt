package io.prumo.mcp.toolsets

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A descrição de cada tool é a única superfície pela qual o Prumo se apresenta ao cliente de IA.
 *
 * O servidor MCP da IDE não transmite texto de apresentação, e as tools do Prumo chegam ao cliente
 * no fim de uma lista longa, disputando a mesma necessidade com as tools nativas. Uma descrição que
 * apenas declara o que a tool faz perde para uma que diz quando usá-la.
 */
@Tag("security")
class ToolDescriptionTest {

    private data class Tool(val name: String, val description: String)

    private val tools: List<Tool> = listOf(
        WorkspaceToolset::class.java,
        RepositoryToolset::class.java,
        IdeToolset::class.java,
        DatabaseToolset::class.java,
        PackToolset::class.java,
        PackAuthoringToolset::class.java,
        QualityToolset::class.java,
        KnowledgeToolset::class.java,
    ).flatMap { toolset ->
        toolset.declaredMethods.mapNotNull { method ->
            val name = method.getAnnotation(McpTool::class.java)?.name ?: return@mapNotNull null
            Tool(name, method.getAnnotation(McpDescription::class.java)?.description.orEmpty())
        }
    }

    @Test
    fun `nenhuma descricao entrega o cliente a outra familia de ferramentas`() {
        val cedendo = tools.filter { tool ->
            CESSAO.any { tool.description.contains(it, ignoreCase = true) }
        }

        assertTrue(cedendo.isEmpty(), "descrição cedendo a preferência: ${cedendo.map { it.name }}")
    }

    @Test
    fun `as tools de leitura dizem quando devem ser usadas`() {
        val semInstrucao = tools
            .filter { it.name in INSTRUCTIVE }
            .filterNot { tool -> INSTRUCAO.any { tool.description.contains(it) } }

        assertTrue(semInstrucao.isEmpty(), "descrição sem instrução de uso: ${semInstrucao.map { it.name }}")
    }

    /**
     * O servidor da IDE não transmite texto de apresentação: entre a conexão e a primeira chamada,
     * o cliente recebe do Prumo apenas nomes e descrições. Se nenhuma delas apontar o ponto de
     * partida, ele não existe para quem chega sem contexto.
     */
    @Test
    fun `as tools de entrada apontam o ponto de partida`() {
        val entrada = tools.filter { it.name in ENTRY_POINTS }
        val semRota = entrada.filterNot { it.description.contains(PREPARE) }

        assertTrue(entrada.isNotEmpty(), "nenhuma tool de entrada encontrada")
        assertTrue(semRota.isEmpty(), "tool de entrada sem rota para $PREPARE: ${semRota.map { it.name }}")
    }

    @Test
    fun `o ponto de partida se apresenta como tal`() {
        val prepare = tools.single { it.name == PREPARE }

        assertTrue(prepare.description.contains("Call this first"), "prepare não se anuncia como primeiro passo")
        assertTrue(prepare.description.contains("boundary"), "prepare não explica o que é um workspace")
    }

    /**
     * Descrição é contrato lido por IA: uma garantia escrita ali vale tanto quanto uma no código.
     *
     * A tool de diff chegou a prometer que honrava os caminhos excluídos sem que o código os
     * consultasse. Este teste liga as duas coisas: quem promete a exclusão precisa consultá-la.
     */
    @Test
    fun `tool que promete honrar a exclusao consulta a exclusao no codigo`() {
        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/RepositoryToolset.kt"),
        )
        val prometem = tools.filter { tool ->
            PROMESSA_DE_EXCLUSAO.any { tool.description.contains(it, ignoreCase = true) }
        }

        assertTrue(prometem.isNotEmpty(), "nenhuma descrição promete a exclusão; o teste perdeu o alvo")
        val consultas = Regex("excludedPaths").findAll(fonte).count()
        assertTrue(
            consultas >= prometem.size,
            "descrições que prometem a exclusão: ${prometem.map { it.name }}, " +
                "mas o toolset consulta excludedPaths apenas $consultas vezes",
        )
    }

    /**
     * O catálogo diz o que a IDE sabe procurar; a análise de um arquivo é da `get_file_problems`,
     * do próprio servidor da IDE. Uma descrição que não separasse as duas faria o cliente pedir
     * análise a quem só lista regras.
     */
    @Test
    fun `a tool de catalogo nega executar inspecao e aponta a via da analise`() {
        val catalogo = tools.single { it.name == QUALITY_CATALOG }

        assertTrue(
            NEGACAO_DE_ANALISE.any { catalogo.description.contains(it, ignoreCase = true) },
            "a descrição não nega executar inspeção",
        )
        assertTrue(
            catalogo.description.contains(ANALISE_NATIVA),
            "a descrição não aponta $ANALISE_NATIVA como a via da análise por arquivo",
        )
        assertTrue(
            catalogo.description.contains("not the same as applicable", ignoreCase = true),
            "a descrição não separa estar registrada de ser aplicável",
        )
    }

    /**
     * A resposta do catálogo vale conforme o perfil que a produziu: o do projeto obriga o time
     * inteiro, o da instalação vale para quem abriu a IDE. Prometer o campo e não o entregar faria
     * o cliente atribuir ao time uma regra que é de uma máquina só.
     */
    @Test
    fun `a tool de catalogo promete dizer de qual perfil a resposta veio`() {
        val catalogo = tools.single { it.name == QUALITY_CATALOG }

        listOf("source", "PROJECT", "APPLICATION").forEach { termo ->
            assertTrue(catalogo.description.contains(termo), "a descrição não cita '$termo'")
        }
        assertTrue(
            catalogo.description.contains("unknownFilters") &&
                catalogo.description.contains("knownValues"),
            "a descrição não promete distinguir filtro errado de resultado vazio",
        )

        val reports = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/QualityReports.kt"),
        )
        listOf("profileScope", "unknownFilters", "knownValues").forEach { campo ->
            assertTrue(reports.contains(campo), "a resposta não tem o campo '$campo' prometido")
        }
    }

    /**
     * O que a janela faz com o pedido do cliente é decidido em silêncio: pedido fora da faixa é
     * puxado para dentro dela, e o resto do catálogo fica inalcançável sem novo recorte. Número que
     * a descrição não declara é número que o cliente descobre por experimento, e conclusão tirada
     * de uma janela que ele pensa ser a lista inteira é conclusão errada sobre o catálogo.
     */
    @Test
    fun `a tool de catalogo declara a faixa da janela que o codigo aplica`() {
        val catalogo = tools.single { it.name == QUALITY_CATALOG }

        assertTrue(
            catalogo.description.contains(QualityReports.MAX_INSPECTIONS.toString()),
            "a descrição não declara o teto de ${QualityReports.MAX_INSPECTIONS} da janela",
        )
        assertTrue(
            catalogo.description.contains(QualityReports.DEFAULT_MAX_RESULTS.toString()),
            "a descrição não declara o padrão de ${QualityReports.DEFAULT_MAX_RESULTS}",
        )
        assertTrue(
            catalogo.description.contains("no paging", ignoreCase = true),
            "a descrição não avisa que a janela não pagina",
        )
    }

    /**
     * `knownValues` responde por `severity` e `language`, e não por `group`: uma instalação tem
     * centenas de grupos. Prometer os valores aceitos sem essa ressalva deixa quem errou o grupo
     * sabendo que errou e sem saber o que acertar.
     */
    @Test
    fun `a tool de catalogo diz para quais criterios ela lista os valores aceitos`() {
        val catalogo = tools.single { it.name == QUALITY_CATALOG }

        assertTrue(
            catalogo.description.contains("severity and language"),
            "a descrição não limita knownValues a severity e language",
        )
        assertTrue(
            catalogo.description.contains("byGroup"),
            "a descrição não aponta byGroup como a via para os grupos",
        )
    }

    /**
     * Descrição é contrato: a tool promete não executar inspeção, e o código precisa cumprir.
     * A API que executa está a um import de distância — `InspectionEngine` e `runInspectionOnFile`
     * vivem no mesmo pacote que a enumeração usa.
     */
    @Test
    fun `quem promete nao executar inspecao nao alcanca a API que executa`() {
        val fontes = listOf(
            "src/main/kotlin/io/prumo/mcp/toolsets/QualityToolset.kt",
            "src/main/kotlin/io/prumo/mcp/toolsets/QualityReports.kt",
            "src/main/kotlin/io/prumo/mcp/ide/InspectionCatalogService.kt",
            "src/main/kotlin/io/prumo/mcp/quality/InspectionRecord.kt",
            "src/main/kotlin/io/prumo/mcp/quality/InspectionCatalog.kt",
        ).associateWith { java.nio.file.Files.readString(java.nio.file.Path.of(it)) }

        val infratores = fontes.filterValues { fonte ->
            EXECUCAO_DE_ANALISE.any { fonte.contains(it) }
        }.keys

        assertTrue(infratores.isEmpty(), "a família do catálogo alcança a API de execução: $infratores")
    }

    /**
     * A base de conhecimento é escrita por uma IA, sem consentimento humano por item. O que sustenta
     * isso não é confiança no conteúdo — é o Prumo declarar, na própria descrição, o que ele garante
     * e o que ele se recusa a garantir.
     */
    @Test
    fun `a tool que grava conhecimento declara o que o Prumo nao garante`() {
        val remember = tools.single { it.name == KNOWLEDGE_REMEMBER }

        assertTrue(
            remember.description.contains("does NOT guarantee"),
            "a descrição não declara o que o Prumo se recusa a garantir",
        )
        assertTrue(
            remember.description.contains("faithful"),
            "não diz que a fidelidade do resumo não é garantida",
        )
        assertTrue(
            remember.description.contains("it cites, it does not substitute"),
            "não diz que o registro cita a fonte em vez de substituí-la",
        )
        assertTrue(
            remember.description.contains("packs are the channel"),
            "não encaminha para o pack o material que vem de fora da fronteira",
        )
    }

    /**
     * A validação em campo de 2026-09-07 pegou a descrição prometendo que conhecimento não derivado
     * da fonte seria recusado. Não é o que acontece, nem o que pode acontecer: o Prumo carimba a
     * origem, não julga o conteúdo. Um agente cego gravou um texto inventado carimbado num arquivo
     * real e foi aceito — corretamente.
     */
    @Test
    fun `a tool que grava nao promete recusar conteudo que nao decorre da fonte`() {
        val remember = tools.single { it.name == KNOWLEDGE_REMEMBER }

        assertFalse(
            remember.description.contains("Knowledge that does not derive from a source"),
            "a descrição promete um controle de conteúdo que o produto não faz",
        )
        assertTrue(
            remember.description.contains("it cannot check that your text follows from there"),
            "não declara que a fidelidade não é conferida",
        )
        assertTrue(
            remember.description.contains("a source it cannot reach"),
            "não diz o que de fato é recusado: fonte inalcançável",
        )
    }

    /**
     * Um agente cego leu `FRESH` como "o Prumo conferiu isto" e guardou um registro apontando para
     * um PDF cujo texto o próprio Prumo declara não extrair — e recebeu `FRESH`, corretamente,
     * porque o arquivo não mudou. A descrição precisa dizer de que o veredicto fala.
     */
    @Test
    fun `a busca explica que frescor fala dos bytes da fonte, nao do conteudo do registro`() {
        val recall = tools.single { it.name == KNOWLEDGE_RECALL }

        assertTrue(
            recall.description.contains("about the bytes of the source"),
            "não diz que o veredicto é sobre o arquivo",
        )
        assertTrue(
            recall.description.contains("never that Prumo checked the text"),
            "não desfaz a leitura de que FRESH significaria conteúdo conferido",
        )
    }

    /**
     * O veredicto de frescor só serve se o cliente souber o que fazer com cada valor. Os três
     * precisam estar explicados onde ele os encontra.
     */
    @Test
    fun `a tool de busca explica os tres veredictos de frescor`() {
        val recall = tools.single { it.name == KNOWLEDGE_RECALL }

        listOf("FRESH", "STALE", "ORPHAN").forEach { veredicto ->
            assertTrue(recall.description.contains(veredicto), "não explica $veredicto")
        }
        assertTrue(
            recall.description.contains("embedding"),
            "não declara que a recuperação não usa embedding",
        )
        assertTrue(
            recall.description.contains("deterministic"),
            "não declara que a recuperação é determinística",
        )
    }

    /**
     * A busca passou a ordenar por relevância sobre título, etiquetas e corpo. Quem lê a descrição
     * precisa saber três coisas para usar o resultado: que o casamento não é literal, que ele não é
     * feito por modelo, e que etiqueta, fonte e frescor continuam recortes exatos — misturar os dois
     * regimes faria o cliente desconfiar de um filtro que não errou.
     */
    @Test
    fun `a tool de busca declara o que passou a casar por relevancia e o que segue exato`() {
        val recall = tools.single { it.name == KNOWLEDGE_RECALL }

        listOf("title, tags and body", "stemming", "BM25").forEach { termo ->
            assertTrue(recall.description.contains(termo), "a descrição não cita '$termo'")
        }
        assertTrue(
            recall.description.contains("exact filters"),
            "a descrição não separa o que continua sendo filtro exato",
        )

        val toolset = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/KnowledgeToolset.kt"),
        )
        assertFalse(
            toolset.contains("record.title.contains("),
            "a busca ainda casa substring de título, contra o que a descrição promete",
        )
    }

    /**
     * Descrição é contrato: quem promete carimbar a fonte por conta própria não pode aceitar o
     * carimbo vindo do cliente, senão a procedência provaria apenas o que a IA disse.
     */
    @Test
    fun `quem promete carimbar a fonte nao aceita carimbo do cliente`() {
        val remember = tools.single { it.name == KNOWLEDGE_REMEMBER }
        assertTrue(remember.description.contains("Prumo stamps the source itself"))

        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/KnowledgeToolset.kt"),
        )
        val assinatura = fonte.substringAfter("suspend fun remember(").substringBefore("): KnowledgeWriteResponse")

        listOf("sha256", "sizeBytes", "modifiedAt", "stamp").forEach { proibido ->
            assertFalse(
                assinatura.contains(proibido, ignoreCase = true),
                "a tool aceita '$proibido' do cliente, e o carimbo deixaria de ser prova",
            )
        }
    }

    /**
     * A contagem do que está fora de alcance é dita à IA, e por isso precisa ser dita inteira: que
     * ela cobre a base e não a consulta é o que a impede de virar oráculo sobre o texto excluído.
     */
    @Test
    fun `a busca declara que nao alcanca o excluido, e o tira antes de pontuar`() {
        val recall = tools.single { it.name == KNOWLEDGE_RECALL }

        listOf("outOfReachCount", "never over your query").forEach { termo ->
            assertTrue(recall.description.contains(termo), "a descrição não cita '$termo'")
        }

        val busca = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/KnowledgeToolset.kt"),
        ).substringAfter("\"knowledge.recall\"").substringBefore("@McpTool(name = READ_TOOL)")

        assertTrue(
            busca.contains("SourceStampReader.outOfReach"),
            "a busca pontua e lista sem perguntar o que está fora de alcance",
        )

        val composicao = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/knowledge/KnowledgeRecall.kt"),
        )
        assertTrue(
            composicao.indexOf("filterNot(outOfReach)") < composicao.indexOf("search.search("),
            "o alcance deixou de ser a primeira etapa da composição",
        )
    }

    /**
     * Descrição é contrato: quem promete recusar o registro fora de alcance precisa consultar o
     * alcance antes de montar a resposta, e a recusa não pode devolver a coordenada que a exclusão
     * retira do cliente.
     */
    @Test
    fun `quem promete recusar o fora de alcance consulta o alcance antes de responder`() {
        val read = tools.single { it.name == KNOWLEDGE_READ }

        listOf("out of reach", "excluded", "refused").forEach { termo ->
            assertTrue(read.description.contains(termo, ignoreCase = true), "a descrição não cita '$termo'")
        }

        val leitura = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/KnowledgeToolset.kt"),
        ).substringAfter("\"knowledge.read\"").substringBefore("@McpTool(name = FORGET_TOOL)")

        assertTrue(
            leitura.contains("SourceStampReader.outOfReach"),
            "a leitura devolve o corpo sem perguntar se a fonte ainda está ao alcance",
        )
        assertTrue(
            leitura.contains("PathExcludedException"),
            "a recusa não é a que a trilha grava como recusa",
        )
        val recusa = leitura.substringAfter("PathExcludedException(").substringBefore("KnowledgeReports.detail")
        assertFalse(
            recusa.contains("provenance"),
            "a mensagem de recusa interpola a procedência, e devolve o caminho que a exclusão retira",
        )
    }

    /**
     * A leitura de documentação passou a extrair formato binário. Três coisas precisam estar ditas,
     * porque decidem o que a IA faz com a resposta: que o texto é verbatim, que a coordenada — e não
     * a linha — é o endereço da fonte, e que PDF sem camada de texto é recusado.
     */
    @Test
    fun `a leitura de documentacao declara o que extrai e o que a coordenada endereca`() {
        val leitura = tools.single { it.name == READ_DOCUMENTATION }

        listOf("verbatim", "never a model", "coordinates", "scanned PDF").forEach { termo ->
            assertTrue(leitura.description.contains(termo), "a descrição não cita '$termo'")
        }
        listOf("PDF", "DOCX", "XLSX", "PPTX").forEach { formato ->
            assertTrue(leitura.description.contains(formato), "a descrição não cita '$formato'")
        }

        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/documentation/DocumentationReader.kt"),
        )
        assertTrue(
            fonte.contains("SupportedDocumentFormats.isExtractable(target)"),
            "a leitura promete extrair e não pergunta se o formato é extraível",
        )
        assertTrue(
            fonte.contains("coordinates = rangesOf(window)"),
            "a leitura promete coordenada e não a devolve",
        )
    }

    /**
     * A busca de documentação existe para trocar documento por passagem. Três promessas decidem o
     * que a IA faz com a resposta: o texto é verbatim, a coordenada é o endereço, e a ausência do
     * modelo muda o que "não achei" significa.
     */
    @Test
    fun `a busca de documentacao promete passagem verbatim, com coordenada e com o limite do que responde`() {
        val busca = tools.single { it.name == SEARCH_DOCUMENTATION }

        listOf("verbatim", "never summarises", "coordinate", "semanticAvailable", "pendingSources").forEach { termo ->
            assertTrue(busca.description.contains(termo), "a descrição não cita '$termo'")
        }
        assertTrue(
            busca.description.contains("not an answer yet"),
            "a descrição não diz que resultado vazio com fonte pendente ainda não é resposta",
        )

        val fonteDaBusca = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets/WorkspaceToolset.kt"),
        ).substringAfter("\"workspace.search_documentation\"").substringBefore("@McpTool(name = READ_DOCUMENTATION_TOOL)")
        assertTrue(
            fonteDaBusca.contains("indexWithinBudget("),
            "a descrição promete não segurar a resposta, e o código indexa tudo antes de responder",
        )
        assertTrue(
            fonteDaBusca.contains("pendingSources = pendentes"),
            "a descrição promete dizer o que ficou pendente, e o código não devolve o número",
        )
        assertTrue(
            busca.description.contains("prumo_workspace_read_documentation"),
            "a busca não diz como ler em volta do trecho",
        )

        val fonte = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/ide/LuceneDocumentIndex.kt"),
        )
        assertTrue(
            fonte.contains("add(StoredField(TEXT, entry.chunk.text))"),
            "o índice guarda outra coisa no lugar do texto do documento",
        )
    }

    @Test
    fun `toda tool tem descricao`() {
        val vazias = tools.filter { it.description.isBlank() }

        assertTrue(vazias.isEmpty(), "tool sem descrição: ${vazias.map { it.name }}")
    }

    private companion object {
        /** Formulações que mandam o cliente procurar a resposta fora do Prumo. */
        val CESSAO = listOf(
            "already answer",
            "use the IDE's own",
            "the IDE's own project tools",
        )

        val INSTRUCAO = listOf("Use this tool", "Prefer it over", "Call it")

        /** Formulações que afirmam ao cliente que a tool respeita os caminhos excluídos. */
        val PROMESSA_DE_EXCLUSAO = listOf(
            "paths excluded for this repository",
            "excluded for this repository",
            "paths the developer excluded",
        )

        const val PREPARE = "prumo_workspace_prepare"

        const val QUALITY_CATALOG = "prumo_quality_list_inspections"

        const val KNOWLEDGE_REMEMBER = "prumo_knowledge_remember"

        const val KNOWLEDGE_RECALL = "prumo_knowledge_recall"

        const val KNOWLEDGE_READ = "prumo_knowledge_read"

        const val READ_DOCUMENTATION = "prumo_workspace_read_documentation"

        const val SEARCH_DOCUMENTATION = "prumo_workspace_search_documentation"

        /** A tool nativa da IDE que analisa um arquivo, e que o catálogo não substitui. */
        const val ANALISE_NATIVA = "get_file_problems"

        /** Formulações que negam ao cliente que a tool execute análise. */
        val NEGACAO_DE_ANALISE = listOf("runs no inspection", "does not run", "never runs")

        /** O que a família do catálogo não pode alcançar sem quebrar a promessa da descrição. */
        val EXECUCAO_DE_ANALISE = listOf(
            "InspectionEngine",
            "runInspectionOnFile",
            "inspectEx",
            "GlobalInspectionContext",
            "PsiFile",
            "PsiManager",
        )

        /** Tools por onde um cliente sem contexto chega ao Prumo. */
        val ENTRY_POINTS = setOf(
            "prumo_workspace_get_context",
            "prumo_workspace_get_repositories",
        )

        /**
         * Tools que competem por uma necessidade que a família nativa também atende, e por isso
         * precisam dizer ao cliente quando escolhê-las.
         */
        val INSTRUCTIVE = setOf(
            "prumo_database_describe_table",
            "prumo_database_get_schema",
            "prumo_database_list_available",
            "prumo_database_list_tables",
            "prumo_ide_get_current_context",
            "prumo_knowledge_recall",
            "prumo_quality_list_inspections",
            "prumo_repository_get_branch",
            "prumo_repository_get_diff",
            "prumo_repository_get_status",
            "prumo_repository_get_structure",
            "prumo_repository_read_file",
            "prumo_repository_search_text",
        )
    }
}
