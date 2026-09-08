package io.prumo.mcp.ide

import io.prumo.mcp.documentation.DocumentHit
import io.prumo.mcp.documentation.DocumentIndex
import io.prumo.mcp.documentation.IndexedChunk
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.pt.PortugueseAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntField
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.ByteBuffersDirectory

/**
 * Índice de trechos de documentação sobre o Lucene que a IDE traz.
 *
 * O mesmo índice guarda o texto e o vetor: a busca por palavra e a busca por proximidade rodam sobre
 * a mesma coleção, e não há segundo armazenamento para divergir do primeiro. O grafo HNSW e o campo
 * de vetor vêm do módulo que o produto já declara — nada é empacotado para isso.
 *
 * Diferente do índice da memória, este **não** nasce e morre a cada chamada: montá-lo custa a
 * vetorização de cada trecho, que é o gasto caro da família. Ele vive enquanto a IDE está aberta, e
 * a fonte alterada é substituída inteira, nunca remendada.
 */
class LuceneDocumentIndex : DocumentIndex, AutoCloseable {

    private val directory = ByteBuffersDirectory()
    private val analyzer: Analyzer = PerFieldAnalyzerWrapper(
        EnglishAnalyzer(),
        mapOf(TEXT_PT to PortugueseAnalyzer(), TEXT_EN to EnglishAnalyzer()),
    )

    @Synchronized
    override fun replaceSource(workspaceId: String, documentationId: String, chunks: List<IndexedChunk>) {
        write { writer ->
            writer.deleteDocuments(sourceQuery(workspaceId, documentationId))
            chunks.forEach { writer.addDocument(documentOf(workspaceId, it)) }
        }
    }

    @Synchronized
    override fun removeSource(workspaceId: String, documentationId: String) {
        write { writer -> writer.deleteDocuments(sourceQuery(workspaceId, documentationId)) }
    }

    @Synchronized
    override fun search(
        workspaceId: String,
        query: String,
        vector: FloatArray?,
        maxResults: Int,
    ): List<DocumentHit> {
        require(query.isNotBlank()) { "The search query must not be blank." }
        require(maxResults >= 1) { "maxResults must be 1 or greater." }
        if (size() == 0) {
            return emptyList()
        }

        return DirectoryReader.open(directory).use { reader ->
            val searcher = IndexSearcher(reader).apply { similarity = BM25Similarity() }
            val doWorkspace = TermQuery(Term(WORKSPACE, workspaceId))
            val porPalavra = searcher.search(within(doWorkspace, textQuery(query)), maxResults).scoreDocs
                .map { hitOf(searcher, it.doc, it.score, semantic = false) }
            val porVetor = vector
                ?.let {
                    searcher.search(KnnFloatVectorQuery(VECTOR, it, maxResults, doWorkspace), maxResults).scoreDocs
                }
                ?.map { hitOf(searcher, it.doc, it.score, semantic = true) }
                .orEmpty()
            merge(porPalavra, porVetor, maxResults)
        }
    }

    @Synchronized
    override fun size(): Int =
        runCatching { DirectoryReader.open(directory).use { it.numDocs() } }.getOrDefault(0)

    @Synchronized
    override fun countOf(workspaceId: String, documentationId: String): Int =
        runCatching {
            DirectoryReader.open(directory).use { reader ->
                IndexSearcher(reader).count(sourceQuery(workspaceId, documentationId))
            }
        }.getOrDefault(0)

    @Synchronized
    override fun close() {
        directory.close()
    }

    /**
     * Junta as duas listas mantendo o melhor de cada trecho.
     *
     * Os scores das duas buscas não são comparáveis entre si — um é BM25, o outro é distância de
     * vetor —, então a ordem final é a intercalação das duas, e um trecho que aparece nas duas conta
     * uma vez só, marcado como semântico. Empate desfeito pela fonte e pela linha, para a mesma
     * consulta devolver sempre a mesma ordem.
     */
    private fun merge(byWord: List<DocumentHit>, byVector: List<DocumentHit>, maxResults: Int): List<DocumentHit> {
        val juntos = LinkedHashMap<String, DocumentHit>()
        val maiorLista = maxOf(byWord.size, byVector.size)
        for (posicao in 0 until maiorLista) {
            byVector.getOrNull(posicao)?.let { juntos.putIfAbsent(chaveDe(it), it) }
            byWord.getOrNull(posicao)?.let { juntos.putIfAbsent(chaveDe(it), it) }
        }
        return juntos.values.take(maxResults)
    }

    private fun chaveDe(hit: DocumentHit) = "${hit.documentationId}|${hit.path}|${hit.firstLine}"

    /** O trecho só existe dentro do workspace que o indexou. */
    private fun sourceQuery(workspaceId: String, documentationId: String): BooleanQuery =
        BooleanQuery.Builder()
            .add(TermQuery(Term(WORKSPACE, workspaceId)), BooleanClause.Occur.FILTER)
            .add(TermQuery(Term(SOURCE, documentationId)), BooleanClause.Occur.FILTER)
            .build()

    private fun within(workspace: TermQuery, query: BooleanQuery): BooleanQuery =
        BooleanQuery.Builder()
            .add(workspace, BooleanClause.Occur.FILTER)
            .add(query, BooleanClause.Occur.MUST)
            .build()

    private fun documentOf(workspaceId: String, entry: IndexedChunk): Document = Document().apply {
        add(StringField(WORKSPACE, workspaceId, Field.Store.NO))
        add(StringField(SOURCE, entry.documentationId, Field.Store.YES))
        add(StoredField(PATH, entry.path))
        add(StoredField(COORDINATE, entry.chunk.coordinate.label))
        add(StoredField(TEXT, entry.chunk.text))
        add(IntField(FIRST_LINE, entry.chunk.firstLine, Field.Store.YES))
        add(IntField(LAST_LINE, entry.chunk.lastLine, Field.Store.YES))
        add(TextField(TEXT_PT, entry.chunk.text, Field.Store.NO))
        add(TextField(TEXT_EN, entry.chunk.text, Field.Store.NO))
        entry.vector?.let { add(KnnFloatVectorField(VECTOR, it, VectorSimilarityFunction.COSINE)) }
    }

    private fun hitOf(searcher: IndexSearcher, doc: Int, score: Float, semantic: Boolean): DocumentHit {
        val stored = searcher.storedFields().document(doc)
        return DocumentHit(
            documentationId = stored[SOURCE],
            path = stored[PATH],
            coordinate = stored[COORDINATE],
            text = stored[TEXT],
            firstLine = stored.getField(FIRST_LINE).numericValue().toInt(),
            lastLine = stored.getField(LAST_LINE).numericValue().toInt(),
            score = score,
            semantic = semantic,
        )
    }

    /**
     * A consulta por palavra, nas duas línguas.
     *
     * Nada no documento declara em que língua ele está, então cada trecho é indexado nos dois
     * analisadores e a consulta pergunta aos dois — a mesma escolha que a busca da memória fez.
     */
    private fun textQuery(query: String): BooleanQuery {
        val builder = BooleanQuery.Builder()
        listOf(TEXT_PT, TEXT_EN).forEach { campo ->
            analyze(campo, query).forEach { termo ->
                builder.add(BoostQuery(TermQuery(Term(campo, termo)), 1f), BooleanClause.Occur.SHOULD)
            }
        }
        return builder.build()
    }

    private fun analyze(field: String, text: String): List<String> {
        val terms = mutableListOf<String>()
        analyzer.tokenStream(field, text).use { stream ->
            val attribute = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                terms.add(attribute.toString())
            }
            stream.end()
        }
        return terms
    }

    private fun write(block: (IndexWriter) -> Unit) {
        IndexWriter(directory, IndexWriterConfig(analyzer)).use(block)
    }

    private companion object {
        const val WORKSPACE = "workspace"
        const val SOURCE = "source"
        const val PATH = "path"
        const val COORDINATE = "coordinate"
        const val TEXT = "text"
        const val FIRST_LINE = "firstLine"
        const val LAST_LINE = "lastLine"
        const val TEXT_PT = "textPt"
        const val TEXT_EN = "textEn"
        const val VECTOR = "vector"
    }
}
