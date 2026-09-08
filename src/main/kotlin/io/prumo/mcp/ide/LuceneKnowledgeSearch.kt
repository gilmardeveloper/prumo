package io.prumo.mcp.ide

import io.prumo.mcp.knowledge.KnowledgeHit
import io.prumo.mcp.knowledge.KnowledgeRecord
import io.prumo.mcp.knowledge.KnowledgeSearch
import io.prumo.mcp.knowledge.KnowledgeSearchResult
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.pt.PortugueseAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.ByteBuffersDirectory

/**
 * Recuperação por relevância sobre a memória, com o Lucene que a IDE traz.
 *
 * O índice nasce e morre dentro de cada chamada, sobre os registros recebidos. É a única forma de
 * não ter uma segunda cópia do dado com vida própria, e custa pouco porque a consulta já lia a base
 * inteira antes de filtrar.
 *
 * Cada registro é indexado duas vezes, uma por idioma, porque nada no registro declara em que língua
 * ele foi escrito: o analisador de português reduz "pagamentos" a "pagament" e o de inglês reduz
 * "payments" a "payment", e consultar os dois campos faz a mesma base responder nas duas línguas.
 */
class LuceneKnowledgeSearch : KnowledgeSearch {

    override fun search(records: List<KnowledgeRecord>, query: String): KnowledgeSearchResult {
        require(query.isNotBlank()) { "The search query must not be blank." }

        val terms = LANGUAGES.flatMap { language -> analyze(language.titleField, query) }.distinct()
        if (records.isEmpty() || terms.isEmpty()) {
            return KnowledgeSearchResult(terms, emptyList())
        }

        return ByteBuffersDirectory().use { directory ->
            index(directory, records)
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader).apply { similarity = BM25Similarity() }
                val top = searcher.search(query(query), records.size)
                KnowledgeSearchResult(
                    terms = terms,
                    hits = top.scoreDocs
                        .map { hit ->
                            KnowledgeHit(searcher.storedFields().document(hit.doc)[ID_FIELD], hit.score)
                        }
                        // O Lucene já entrega por score, mas não diz o que fazer com empate. Sem o
                        // desempate pelo identificador, duas chamadas iguais podem trocar a ordem.
                        .sortedWith(compareByDescending<KnowledgeHit> { it.score }.thenBy { it.id }),
                )
            }
        }
    }

    private fun index(directory: ByteBuffersDirectory, records: List<KnowledgeRecord>) {
        IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
            records.forEach { record ->
                val document = Document()
                document.add(StoredField(ID_FIELD, record.id))
                LANGUAGES.forEach { language ->
                    // Campo em branco entra na média de comprimento do campo e distorce o BM25 de
                    // quem o preencheu: registro sem etiqueta faria a etiqueta dos outros valer
                    // menos que o corpo deles.
                    add(document, language.titleField, record.title)
                    add(document, language.tagsField, record.tags.joinToString(" "))
                    add(document, language.bodyField, record.body)
                }
                writer.addDocument(document)
            }
        }
    }

    private fun add(document: Document, field: String, value: String) {
        if (value.isNotBlank()) {
            document.add(TextField(field, value, Field.Store.NO))
        }
    }

    /**
     * A consulta montada campo a campo.
     *
     * Não há `queryparser` no módulo da plataforma, e é melhor assim: o cliente escreve texto, não
     * sintaxe de busca, e nada do vocabulário do Lucene chega à superfície MCP.
     *
     * Os pesos são os mesmos que a busca de pack já usa — título acima de etiqueta, etiqueta acima
     * de corpo —, para que o produto tenha uma noção só de relevância. Eles inclinam o resultado,
     * não o decidem: o BM25 pontua cada campo pela raridade do termo naquele campo e pelo tamanho
     * do texto, então um termo raro no corpo pode superar um comum no título.
     */
    private fun query(text: String): BooleanQuery {
        val query = BooleanQuery.Builder()
        LANGUAGES.forEach { language ->
            listOf(
                language.titleField to TITLE_WEIGHT,
                language.tagsField to TAG_WEIGHT,
                language.bodyField to BODY_WEIGHT,
            ).forEach { (field, weight) ->
                analyze(field, text).forEach { term ->
                    query.add(BoostQuery(TermQuery(Term(field, term)), weight), BooleanClause.Occur.SHOULD)
                }
            }
        }
        return query.build()
    }

    /** Os termos em que o analisador daquele campo reduz o texto. */
    private fun analyze(field: String, text: String): List<String> =
        analyzer.tokenStream(field, text).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            buildList {
                while (stream.incrementToken()) {
                    add(term.toString())
                }
                stream.end()
            }
        }

    /**
     * Um idioma indexado, com os três campos que ele ocupa.
     *
     * Título, etiqueta e corpo são campos separados porque respondem com pesos diferentes; o idioma
     * separa porque cada analisador tem o seu stemming e as suas palavras vazias.
     */
    private enum class Language(private val suffix: String) {
        PORTUGUESE("pt"),
        ENGLISH("en"),
        ;

        val titleField: String get() = "title_$suffix"
        val tagsField: String get() = "tags_$suffix"
        val bodyField: String get() = "body_$suffix"
    }

    private companion object {
        const val ID_FIELD = "id"
        const val TITLE_WEIGHT = 3f
        const val TAG_WEIGHT = 2f
        const val BODY_WEIGHT = 1f

        val LANGUAGES = Language.entries

        /**
         * As palavras vazias das duas línguas, em todos os campos.
         *
         * Sem a união, "de" e "o" sobrevivem no campo indexado em inglês e "the" sobrevive no
         * indexado em português: procurar por uma palavra vazia de um idioma devolveria resultado
         * pelo campo do outro.
         */
        val stopWords = CharArraySet(
            PortugueseAnalyzer.getDefaultStopSet().size + EnglishAnalyzer.getDefaultStopSet().size,
            true,
        ).apply {
            addAll(PortugueseAnalyzer.getDefaultStopSet())
            addAll(EnglishAnalyzer.getDefaultStopSet())
        }

        val analyzer: Analyzer = PerFieldAnalyzerWrapper(
            EnglishAnalyzer(stopWords),
            Language.entries.flatMap { language ->
                val analyzer: Analyzer = when (language) {
                    Language.PORTUGUESE -> PortugueseAnalyzer(stopWords)
                    Language.ENGLISH -> EnglishAnalyzer(stopWords)
                }
                listOf(language.titleField, language.tagsField, language.bodyField).map { it to analyzer }
            }.toMap(),
        )
    }
}
