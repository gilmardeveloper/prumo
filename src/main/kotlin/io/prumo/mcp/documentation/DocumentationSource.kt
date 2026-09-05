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
 * Formatos que o Prumo lê como texto.
 *
 * PDF é aceito para registro, mas o conteúdo não é extraído: o documento fica catalogado.
 */
object SupportedDocumentFormats {

    private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "json", "yaml", "yml", "adoc", "csv")
    private val CATALOG_ONLY_EXTENSIONS = setOf("pdf")

    fun isSupported(path: Path): Boolean = extensionOf(path) in TEXT_EXTENSIONS + CATALOG_ONLY_EXTENSIONS

    fun isReadableAsText(path: Path): Boolean = extensionOf(path) in TEXT_EXTENSIONS

    private fun extensionOf(path: Path): String =
        path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
}
