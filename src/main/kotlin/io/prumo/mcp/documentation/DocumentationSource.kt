package io.prumo.mcp.documentation

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.Locale

/** Peso que a documentação tem como fonte de verdade. */
@Serializable
enum class DocumentAuthority {
    OFFICIAL,
    REFERENCE,
    GENERATED,
}

@Serializable
enum class DocumentationKind {
    FILE,
    DIRECTORY,
}

/**
 * Documento ou pasta de documentos associada a um workspace.
 *
 * Pertence a um único workspace e nunca é compartilhada implicitamente com outro.
 */
@Serializable
data class DocumentationSource(
    val id: String,
    val name: String,
    val kind: DocumentationKind,
    val location: String,
    val authority: DocumentAuthority = DocumentAuthority.REFERENCE,
) {
    init {
        require(id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "Invalid documentation id '$id'." }
        require(name.isNotBlank()) { "Documentation name must not be blank." }
        require(location.isNotBlank()) { "Documentation location must not be blank." }
    }
}

/**
 * Formatos que o Prumo lê.
 *
 * São dois modos: texto puro, entregue como está, e formato binário, de que se extrai o conteúdo e
 * se descarta o resto — estilo, tema, propriedade do documento e desenho. Extensão fora dos dois
 * grupos é aceita para registro, e o documento fica catalogado sem ser lido.
 */
object SupportedDocumentFormats {

    private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "json", "yaml", "yml", "adoc", "csv")
    private val EXTRACTABLE_EXTENSIONS = setOf("pdf", "docx", "xlsx", "pptx")

    fun isSupported(path: Path): Boolean = extensionOf(path) in TEXT_EXTENSIONS + EXTRACTABLE_EXTENSIONS

    fun isReadableAsText(path: Path): Boolean = isSupported(path)

    /** Formato binário de que o conteúdo é extraído, em vez de lido como está. */
    fun isExtractable(path: Path): Boolean = extensionOf(path) in EXTRACTABLE_EXTENSIONS

    private fun extensionOf(path: Path): String =
        path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
}
