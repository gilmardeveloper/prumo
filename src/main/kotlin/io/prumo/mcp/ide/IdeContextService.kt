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
 * O que o editor tem aberto no instante da chamada.
 *
 * Não achar posição tem duas causas diferentes, e quem responde ao cliente precisa das duas
 * separadas: não haver editor de texto selecionado, e o que está aberto não viver em disco.
 */
sealed interface EditorState {

    /** Nenhum editor de texto está selecionado. */
    data object NoEditor : EditorState

    /** O que está aberto não tem caminho em disco: conteúdo de jar, scratch ou sistema remoto. */
    data object NotOnDisk : EditorState

    /** O cursor está num arquivo do disco. */
    data class At(val snapshot: EditorSnapshot) : EditorState
}

/**
 * Leitura do estado do editor.
 *
 * O estado é lido no instante da chamada e devolvido uma vez. Nada é observado continuamente.
 */
object IdeContextService {

    suspend fun currentEditor(project: Project): EditorState {
        val position = when (val caret = withContext(Dispatchers.EDT) { readCaret(project) }) {
            is CaretLookup.Found -> caret.position
            CaretLookup.NoEditor -> return EditorState.NoEditor
            CaretLookup.NotOnDisk -> return EditorState.NotOnDisk
        }
        val (symbols, language) = readAction { readSymbols(project, position) }
        val module = readAction { ModuleUtilCore.findModuleForFile(position.file, project)?.name }

        return EditorState.At(
            EditorSnapshot(
                absolutePath = position.absolutePath,
                line = position.line,
                column = position.column,
                selectionStartLine = position.selectionStartLine,
                selectionEndLine = position.selectionEndLine,
                selectionLength = position.selectionLength,
                symbolPath = symbols,
                language = language,
                moduleName = module,
            ),
        )
    }

    /** O que a leitura do cursor encontrou, com a causa quando não encontrou posição. */
    private sealed interface CaretLookup {
        data object NoEditor : CaretLookup
        data object NotOnDisk : CaretLookup
        data class Found(val position: CaretPosition) : CaretLookup
    }

    private fun readCaret(project: Project): CaretLookup {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return CaretLookup.NoEditor
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return CaretLookup.NoEditor
        // Arquivo dentro de jar, de sistema remoto ou de scratch não tem caminho em disco.
        val path = runCatching { file.toNioPath().toString() }.getOrNull() ?: return CaretLookup.NotOnDisk

        val caret = editor.caretModel.primaryCaret
        val logical = caret.logicalPosition
        val hasSelection = caret.hasSelection()
        return CaretLookup.Found(
            CaretPosition(
                file = file,
                absolutePath = path,
                offset = caret.offset,
                line = logical.line + 1,
                column = logical.column + 1,
                selectionStartLine = if (hasSelection) document.getLineNumber(caret.selectionStart) + 1 else null,
                selectionEndLine = if (hasSelection) document.getLineNumber(caret.selectionEnd) + 1 else null,
                selectionLength = if (hasSelection) caret.selectionEnd - caret.selectionStart else null,
            ),
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
