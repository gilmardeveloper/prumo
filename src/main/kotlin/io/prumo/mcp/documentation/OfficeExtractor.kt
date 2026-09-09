package io.prumo.mcp.documentation

import java.io.InputStream
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.name

/**
 * Extrai o texto de um documento do formato Office aberto — `docx`, `xlsx` e `pptx`.
 *
 * O formato é um zip com XML dentro, então a extração usa apenas o que a JDK já traz: nenhuma
 * biblioteca é empacotada para isso. O que sai é conteúdo; estilo, tema, relação, propriedade do
 * documento e desenho ficam no arquivo.
 *
 * O arquivo é entrada hostil e é tratado como tal: DTD e entidade externa desligados no leitor de
 * XML, nome de entrada que escapa do pacote recusado, e teto de bytes descomprimidos.
 */
object OfficeExtractor {

    /** Extensões que esta família sabe abrir. */
    private val EXTENSIONS = setOf("docx", "xlsx", "pptx")

    /** Teto do que se aceita descomprimir de um único documento. */
    private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

    private const val WORD_DOCUMENT = "word/document.xml"
    private const val WORKBOOK = "xl/workbook.xml"
    private const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val SLIDE_PREFIX = "ppt/slides/slide"

    fun handles(file: Path): Boolean = extensionOf(file) in EXTENSIONS

    /**
     * O conteúdo do documento, linha a linha, com a coordenada de cada uma.
     *
     * @throws DocumentationReadException quando o arquivo não é um pacote válido, quando a peça de
     *   conteúdo não está onde o formato manda, ou quando o pacote passa dos limites aceitos.
     */
    fun extract(file: Path): ExtractedDocument = when (extensionOf(file)) {
        "docx" -> open(file) { zip -> ExtractedDocument(paragraphsOf(zip, file)) }
        "xlsx" -> open(file) { zip -> ExtractedDocument(rowsOf(zip, file)) }
        "pptx" -> open(file) { zip -> ExtractedDocument(slidesOf(zip, file)) }
        else -> throw DocumentationReadException(
            "Prumo does not extract text from '${file.name}': it is not an Office Open XML document.",
        )
    }

    private fun <T> open(file: Path, block: (ZipFile) -> T): T =
        try {
            ZipFile(file.toFile()).use { zip ->
                assertSaneEntries(zip, file)
                block(zip)
            }
        } catch (failure: DocumentationReadException) {
            throw failure
        } catch (failure: XMLStreamException) {
            throw DocumentationReadException(
                "Prumo could not read the XML inside '${file.name}': the document is malformed.",
            )
        } catch (failure: java.io.IOException) {
            throw DocumentationReadException("Prumo could not open '${file.name}' as an Office document.")
        }

    /**
     * Recusa o pacote inteiro quando alguma entrada não pode ser confiada.
     *
     * Nome que sobe de diretório ou que é absoluto não tem leitura legítima dentro de um documento,
     * e razão de descompressão fora de escala é bomba, não documento.
     */
    private fun assertSaneEntries(zip: ZipFile, file: Path) {
        var descomprimido = 0L
        zip.entries().asSequence().forEach { entry ->
            if (escapes(entry.name)) {
                throw DocumentationReadException(
                    "Prumo refused '${file.name}': it carries an entry that points outside the document.",
                )
            }
            descomprimido += maxOf(entry.size, 0)
            if (descomprimido > MAX_UNCOMPRESSED_BYTES) {
                throw DocumentationReadException(
                    "Prumo refused '${file.name}': it expands beyond the " +
                        "${MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB Prumo reads from one document.",
                )
            }
        }
    }

    private fun escapes(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.startsWith("/") ||
            normalized.contains(":") ||
            normalized.split('/').any { it == ".." }
    }

    private fun paragraphsOf(zip: ZipFile, file: Path): List<ExtractedLine> {
        val entry = zip.getEntry(WORD_DOCUMENT) ?: throw missingPart(file, WORD_DOCUMENT)
        val paragraphs = mutableListOf<String>()
        val atual = StringBuilder()
        read(zip, entry) { reader ->
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "t" -> atual.append(reader.elementText)
                        "tab" -> atual.append('\t')
                    }
                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "p") {
                        paragraphs.add(atual.toString())
                        atual.setLength(0)
                    }
                }
            }
        }
        return paragraphs
            .mapIndexed { indice, texto -> ExtractedLine(SourceCoordinate.Paragraph(indice + 1), texto.trim()) }
            .filter { it.text.isNotEmpty() }
    }

    private fun rowsOf(zip: ZipFile, file: Path): List<ExtractedLine> {
        val shared = sharedStringsOf(zip)
        val linhas = mutableListOf<ExtractedLine>()
        sheetsOf(zip, file).forEach { (nome, entry) ->
            var linha = 0
            var celulas = mutableListOf<String>()
            var tipo: String? = null
            read(zip, entry) { reader ->
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                            "row" -> {
                                linha = reader.getAttributeValue(null, "r")?.toIntOrNull() ?: (linha + 1)
                                celulas = mutableListOf()
                            }
                            "c" -> tipo = reader.getAttributeValue(null, "t")
                            "v" -> celulas.add(valueOf(reader.elementText, tipo, shared))
                            "t" -> if (tipo == "inlineStr") celulas.add(reader.elementText)
                        }
                        XMLStreamConstants.END_ELEMENT -> if (reader.localName == "row") {
                            val texto = celulas.joinToString("\t").trimEnd('\t')
                            if (texto.isNotBlank()) {
                                linhas.add(ExtractedLine(SourceCoordinate.Row(nome, linha), texto))
                            }
                        }
                    }
                }
            }
        }
        return linhas
    }

    private fun valueOf(bruto: String, tipo: String?, shared: List<String>): String =
        if (tipo == "s") bruto.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty() else bruto

    private fun sharedStringsOf(zip: ZipFile): List<String> {
        val entry = zip.getEntry(SHARED_STRINGS) ?: return emptyList()
        val textos = mutableListOf<String>()
        val atual = StringBuilder()
        read(zip, entry) { reader ->
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> if (reader.localName == "t") atual.append(reader.elementText)
                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "si") {
                        textos.add(atual.toString())
                        atual.setLength(0)
                    }
                }
            }
        }
        return textos
    }

    /**
     * As abas da planilha, na ordem do arquivo, com o nome que o usuário vê.
     *
     * O nome está no `workbook.xml` e o arquivo da aba, no `workbook.xml.rels`: um lado guarda o
     * rótulo, o outro guarda o caminho, e a ligação é o identificador de relação.
     */
    private fun sheetsOf(zip: ZipFile, file: Path): List<Pair<String, ZipEntry>> {
        val workbook = zip.getEntry(WORKBOOK) ?: throw missingPart(file, WORKBOOK)
        val abas = mutableListOf<Pair<String, String>>()
        read(zip, workbook) { reader ->
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "sheet") {
                    val nome = reader.getAttributeValue(null, "name").orEmpty()
                    val relacao = (0 until reader.attributeCount)
                        .firstOrNull { reader.getAttributeLocalName(it) == "id" }
                        ?.let { reader.getAttributeValue(it) }
                        .orEmpty()
                    abas.add(nome to relacao)
                }
            }
        }

        val alvos = mutableMapOf<String, String>()
        zip.getEntry(WORKBOOK_RELS)?.let { rels ->
            read(zip, rels) { reader ->
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "Relationship") {
                        val id = reader.getAttributeValue(null, "Id").orEmpty()
                        val alvo = reader.getAttributeValue(null, "Target").orEmpty()
                        alvos[id] = alvo
                    }
                }
            }
        }

        return abas.mapNotNull { (nome, relacao) ->
            val alvo = alvos[relacao] ?: return@mapNotNull null
            val caminho = "xl/" + alvo.removePrefix("/xl/").removePrefix("./")
            zip.getEntry(caminho)?.let { nome to it }
        }
    }

    private fun slidesOf(zip: ZipFile, file: Path): List<ExtractedLine> {
        val slides = zip.entries().asSequence()
            .filter { it.name.startsWith(SLIDE_PREFIX) && it.name.endsWith(".xml") }
            .sortedBy { it.name.removePrefix(SLIDE_PREFIX).removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
        if (slides.isEmpty()) {
            throw missingPart(file, "$SLIDE_PREFIX*.xml")
        }

        val linhas = mutableListOf<ExtractedLine>()
        slides.forEachIndexed { indice, entry ->
            read(zip, entry) { reader ->
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "t") {
                        val texto = reader.elementText.trim()
                        if (texto.isNotEmpty()) {
                            linhas.add(ExtractedLine(SourceCoordinate.Slide(indice + 1), texto))
                        }
                    }
                }
            }
        }
        return linhas
    }

    private fun missingPart(file: Path, part: String) = DocumentationReadException(
        "Prumo could not find '$part' inside '${file.name}': the document is not a complete package.",
    )

    private fun read(zip: ZipFile, entry: ZipEntry, block: (XMLStreamReader) -> Unit) {
        zip.getInputStream(entry).use { stream -> readXml(stream, block) }
    }

    private fun readXml(stream: InputStream, block: (XMLStreamReader) -> Unit) {
        val reader = XML_FACTORY.createXMLStreamReader(stream)
        try {
            block(reader)
        } finally {
            reader.close()
        }
    }

    private fun extensionOf(file: Path): String =
        file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()

    /**
     * Leitor de XML sem DTD e sem entidade externa.
     *
     * XML de arquivo alheio que resolve entidade externa lê o disco de quem abriu o documento. As
     * duas propriedades são desligadas aqui, uma vez, para todas as peças de todos os formatos.
     */
    private val XML_FACTORY: XMLInputFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }
}
