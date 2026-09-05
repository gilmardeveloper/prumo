package io.prumo.mcp.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Onde o desenvolvedor está no editor, em vocabulário neutro e sem tipos da IDE. */
data class EditorSnapshot(
    val absolutePath: String,
    val line: Int,
    val column: Int,
    val selectionStartLine: Int?,
    val selectionEndLine: Int?,
    val selectionLength: Int?,
    val symbolPath: List<String>,
    val language: String?,
    val moduleName: String?,
)

/**
 * Leitura do estado do editor.
 *
 * O estado é lido no instante da chamada e devolvido uma vez. Nada é observado continuamente.
 */
object IdeContextService {

    suspend fun currentEditor(project: Project): EditorSnapshot? {
        val position = withContext(Dispatchers.EDT) { readCaret(project) } ?: return null
        val (symbols, language) = readAction { readSymbols(project, position) }
        val module = readAction { ModuleUtilCore.findModuleForFile(position.file, project)?.name }

        return EditorSnapshot(
            absolutePath = position.absolutePath,
            line = position.line,
            column = position.column,
            selectionStartLine = position.selectionStartLine,
            selectionEndLine = position.selectionEndLine,
            selectionLength = position.selectionLength,
            symbolPath = symbols,
            language = language,
            moduleName = module,
        )
    }

    private fun readCaret(project: Project): CaretPosition? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        // Arquivo dentro de jar, de sistema remoto ou de scratch não tem caminho em disco.
        val path = runCatching { file.toNioPath().toString() }.getOrNull() ?: return null

        val caret = editor.caretModel.primaryCaret
        val logical = caret.logicalPosition
        val hasSelection = caret.hasSelection()
        return CaretPosition(
            file = file,
            absolutePath = path,
            offset = caret.offset,
            line = logical.line + 1,
            column = logical.column + 1,
            selectionStartLine = if (hasSelection) document.getLineNumber(caret.selectionStart) + 1 else null,
            selectionEndLine = if (hasSelection) document.getLineNumber(caret.selectionEnd) + 1 else null,
            selectionLength = if (hasSelection) caret.selectionEnd - caret.selectionStart else null,
        )
    }

    /**
     * Nomes que contêm o cursor, do mais externo ao mais interno.
     *
     * A travessia usa `PsiNameIdentifierOwner`, comum a qualquer linguagem com PSI.
     */
    private fun readSymbols(project: Project, position: CaretPosition): Pair<List<String>, String?> {
        val psiFile = PsiManager.getInstance(project).findFile(position.file) ?: return emptyList<String>() to null
        val element = psiFile.findElementAt(position.offset)
        val names = generateSequence(element) { it.parent }
            .filterIsInstance<PsiNameIdentifierOwner>()
            .mapNotNull { it.name }
            .toList()
            .asReversed()
        return names to psiFile.language.displayName
    }

    private data class CaretPosition(
        val file: VirtualFile,
        val absolutePath: String,
        val offset: Int,
        val line: Int,
        val column: Int,
        val selectionStartLine: Int?,
        val selectionEndLine: Int?,
        val selectionLength: Int?,
    )
}
