package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A extração dos formatos Office abertos.
 *
 * Dois grupos de garantia: o conteúdo sai com a coordenada certa e sem o metadado que infla, e o
 * arquivo é tratado como entrada hostil — quem abre documento alheio abre o que o outro escreveu.
 */
@Tag("security")
class OfficeExtractorTest {

    @Test
    fun `o docx sai por paragrafo, sem estilo nem propriedade`(@TempDir root: Path) {
        val arquivo = docx(
            root,
            """<w:document xmlns:w="urn:w"><w:body>
                 <w:p><w:r><w:t>Regra da rubrica 662</w:t></w:r></w:p>
                 <w:p><w:r><w:t></w:t></w:r></w:p>
                 <w:p><w:r><w:t>Vale para o evento </w:t></w:r><w:r><w:t>S-1200</w:t></w:r></w:p>
               </w:body></w:document>""",
        )

        val documento = OfficeExtractor.extract(arquivo)

        assertEquals(
            listOf("Regra da rubrica 662", "Vale para o evento S-1200"),
            documento.lines.map { it.text },
        )
        assertEquals(SourceCoordinate.Paragraph(1), documento.lines.first().coordinate)
        assertFalse(documento.lines.any { it.text.contains("Calibri") }, "estilo não pode chegar à saída")
    }

    @Test
    fun `o xlsx sai por linha, com a aba e o numero da linha`(@TempDir root: Path) {
        val arquivo = xlsx(root)

        val documento = OfficeExtractor.extract(arquivo)

        assertEquals(listOf("CPF\tNome", "21409765334\tMaria"), documento.lines.map { it.text })
        assertEquals(SourceCoordinate.Row("Casos", 1), documento.lines.first().coordinate)
        assertEquals(SourceCoordinate.Row("Casos", 2), documento.lines.last().coordinate)
    }

    @Test
    fun `o pptx sai por slide`(@TempDir root: Path) {
        val arquivo = pptx(root)

        val documento = OfficeExtractor.extract(arquivo)

        assertEquals(listOf("Folha de pagamento", "Rubricas"), documento.lines.map { it.text })
        assertEquals(SourceCoordinate.Slide(1), documento.lines.first().coordinate)
        assertEquals(SourceCoordinate.Slide(2), documento.lines.last().coordinate)
    }

    /** Nome que sobe de diretório não tem leitura legítima dentro de um documento. */
    @Test
    fun `entrada que aponta para fora do pacote recusa o documento inteiro`(@TempDir root: Path) {
        val arquivo = root.resolve("armadilha.docx")
        zip(arquivo) { saida ->
            entrada(saida, "word/document.xml", "<w:document xmlns:w=\"urn:w\"><w:body/></w:document>")
            entrada(saida, "../../fora.txt", "conteudo alheio")
        }

        val falha = assertThrows<DocumentationReadException> { OfficeExtractor.extract(arquivo) }

        assertTrue(falha.message.orEmpty().contains("points outside"), falha.message.orEmpty())
    }

    /** XML de arquivo alheio que resolve entidade externa lê o disco de quem abriu o documento. */
    @Test
    fun `entidade externa no XML nao e resolvida`(@TempDir root: Path) {
        val alvo = root.resolve("segredo.txt")
        Files.writeString(alvo, "senha do banco")
        val arquivo = docx(
            root,
            """<!DOCTYPE w:document [<!ENTITY furto SYSTEM "${alvo.toUri()}">]>
               <w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>&furto;</w:t></w:r></w:p></w:body></w:document>""",
        )

        val resultado = runCatching { OfficeExtractor.extract(arquivo) }
        val saida = resultado.fold(
            onSuccess = { documento -> documento.lines.joinToString(" ") { it.text } },
            onFailure = { falha -> falha.message.orEmpty() },
        )

        assertFalse(saida.contains("senha do banco"), "a entidade externa foi resolvida e o disco vazou: $saida")
    }

    /**
     * O teste de comportamento acima não distingue o leitor configurado do leitor padrão: nesta JDK,
     * nem com DTD ligado a entidade externa chegou à saída. Como a proteção não pode depender do
     * padrão de uma JDK, o que está preso aqui é a configuração em si.
     */
    @Test
    fun `o leitor de XML nasce sem DTD e sem entidade externa`() {
        val fonte = java.nio.file.Files.readString(
            Path.of("src/main/kotlin/io/prumo/mcp/documentation/OfficeExtractor.kt"),
        )

        assertTrue(
            fonte.contains("setProperty(XMLInputFactory.SUPPORT_DTD, false)"),
            "o leitor de XML aceita DTD, e DTD é a porta da entidade externa e da expansão em cadeia",
        )
        assertTrue(
            fonte.contains("setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)"),
            "o leitor de XML resolve entidade externa",
        )
    }

    @Test
    fun `pacote que estoura ao descomprimir e recusado`(@TempDir root: Path) {
        val arquivo = root.resolve("bomba.docx")
        zip(arquivo) { saida ->
            entrada(saida, "word/document.xml", "<w:document xmlns:w=\"urn:w\"><w:body/></w:document>")
            saida.putNextEntry(ZipEntry("word/enchimento.bin"))
            val bloco = ByteArray(1024 * 1024)
            repeat(70) { saida.write(bloco) }
            saida.closeEntry()
        }

        val falha = assertThrows<DocumentationReadException> { OfficeExtractor.extract(arquivo) }

        assertTrue(falha.message.orEmpty().contains("expands beyond"), falha.message.orEmpty())
    }

    @Test
    fun `pacote sem a peca de conteudo e recusado com o nome dela`(@TempDir root: Path) {
        val arquivo = root.resolve("incompleto.docx")
        zip(arquivo) { saida -> entrada(saida, "docProps/core.xml", "<coreProperties/>") }

        val falha = assertThrows<DocumentationReadException> { OfficeExtractor.extract(arquivo) }

        assertTrue(falha.message.orEmpty().contains("word/document.xml"), falha.message.orEmpty())
    }

    @Test
    fun `formato que esta familia nao abre e recusado`(@TempDir root: Path) {
        val arquivo = root.resolve("antigo.doc")
        Files.writeString(arquivo, "binario antigo")

        assertThrows<DocumentationReadException> { OfficeExtractor.extract(arquivo) }
        assertFalse(OfficeExtractor.handles(arquivo))
        assertTrue(OfficeExtractor.handles(root.resolve("novo.xlsx")))
    }

    /**
     * Sonda sobre a planilha real que motivou a tarefa. Pulada quando o arquivo não está na máquina,
     * porque ele vive fora deste repositório.
     */
    @Test
    fun `a planilha real do campo sai enxuta`() {
        val real = Path.of(
            "C:/projetos-seplag/folha-esocial/docs/investigacoes/74218-dtlaudo/74218_dtLaudo_casos_divergentes.xlsx",
        )
        assumeTrue(Files.exists(real), "planilha de campo ausente nesta máquina")

        val documento = OfficeExtractor.extract(real)
        val texto = documento.lines.joinToString("\n") { it.text }

        assertTrue(documento.lines.size > 50, "linhas: ${documento.lines.size}")
        assertTrue(texto.length < Files.size(real) * 2, "a saída deveria ser menor que o pacote inflado")
        assertFalse(texto.contains("<xdr:"), "desenho não pode chegar à saída")
        assertFalse(texto.contains("theme"), "tema não pode chegar à saída")
    }

    private fun docx(root: Path, documento: String): Path {
        val arquivo = root.resolve("documento.docx")
        zip(arquivo) { saida ->
            entrada(saida, "word/document.xml", documento)
            entrada(saida, "docProps/core.xml", "<coreProperties><creator>Calibri</creator></coreProperties>")
        }
        return arquivo
    }

    private fun xlsx(root: Path): Path {
        val arquivo = root.resolve("planilha.xlsx")
        zip(arquivo) { saida ->
            entrada(
                saida,
                "xl/workbook.xml",
                """<workbook xmlns:r="urn:r"><sheets><sheet name="Casos" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            )
            entrada(
                saida,
                "xl/_rels/workbook.xml.rels",
                """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            )
            entrada(
                saida,
                "xl/sharedStrings.xml",
                """<sst><si><t>CPF</t></si><si><t>Nome</t></si><si><t>Maria</t></si></sst>""",
            )
            entrada(
                saida,
                "xl/worksheets/sheet1.xml",
                """<worksheet><sheetData>
                     <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                     <row r="2"><c r="A2"><v>21409765334</v></c><c r="B2" t="s"><v>2</v></c></row>
                     <row r="3"/>
                   </sheetData></worksheet>""",
            )
            entrada(saida, "xl/styles.xml", "<styleSheet><fonts><font><name val=\"Calibri\"/></font></fonts></styleSheet>")
        }
        return arquivo
    }

    private fun pptx(root: Path): Path {
        val arquivo = root.resolve("apresentacao.pptx")
        zip(arquivo) { saida ->
            entrada(saida, "ppt/slides/slide1.xml", """<sld xmlns:a="urn:a"><a:t>Folha de pagamento</a:t></sld>""")
            entrada(saida, "ppt/slides/slide2.xml", """<sld xmlns:a="urn:a"><a:t>Rubricas</a:t></sld>""")
        }
        return arquivo
    }

    private fun zip(arquivo: Path, bloco: (ZipOutputStream) -> Unit) {
        ZipOutputStream(Files.newOutputStream(arquivo)).use(bloco)
    }

    private fun entrada(saida: ZipOutputStream, nome: String, conteudo: String) {
        saida.putNextEntry(ZipEntry(nome))
        saida.write(conteudo.toByteArray(Charsets.UTF_8))
        saida.closeEntry()
    }

    private fun OutputStream.write(bytes: ByteArray) = write(bytes, 0, bytes.size)
}
