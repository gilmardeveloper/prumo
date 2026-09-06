package io.prumo.mcp.datasource.security

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Categoria de dado pessoal reconhecida na saída de uma consulta.
 *
 * A categoria decide a janela que permanece visível. `NONE` é o valor entregue sem alteração.
 */
@Serializable
enum class PersonalDataKind {
    NONE,
    CPF,
    CNPJ,
    NATIONAL_ID,
    REGISTRY_NUMBER,
    PHONE,
    NAME,
    EMAIL,
    BIRTH_DATE,
    BANK_ACCOUNT,
    /** Dado sensível na acepção da LGPD: origem racial, saúde, deficiência, biometria. */
    SENSITIVE_ATTRIBUTE,
    /** Texto livre que pode conter qualquer coisa sobre a pessoa. */
    FREE_TEXT,
}

/**
 * Ofusca parcialmente o valor de uma coluna de dado pessoal.
 *
 * Preserva o suficiente para a IA reconhecer o mesmo registro entre consultas e entender a natureza
 * do campo, e esconde o suficiente para o documento não ser reconstruído. A função é pura: o mesmo
 * valor produz sempre a mesma saída, sem estado e sem relógio.
 *
 * Dígito verificador nunca é exibido. Ele é função dos demais dígitos, então não acrescenta
 * informação de negócio e permite conferir um palpite vindo de outra fonte.
 *
 * A ofuscação é aplicada depois de o banco ter resolvido a consulta, de modo que junção,
 * agrupamento, filtro e ordenação continuam operando sobre o valor real.
 *
 * Dois valores que diferem apenas nos dígitos escondidos saem iguais. Valor ofuscado não é chave:
 * igualdade na saída não prova igualdade na origem.
 */
object PersonalDataObfuscator {

    private const val HIDDEN = '*'

    /** Marcador de valor escondido por inteiro. Tamanho fixo: o comprimento real é informação. */
    const val HIDDEN_VALUE = "[hidden]"

    /**
     * Decide a categoria de uma coluna pelo nome, confirmando pelo formato do valor.
     *
     * O nome é o gatilho e o formato é a confirmação. Quando o nome sugere um documento e o valor
     * não tem o formato correspondente, o valor é tratado como [PersonalDataKind.REGISTRY_NUMBER] —
     * a janela mais restritiva entre as numéricas — em vez de ser liberado.
     */
    fun classify(columnName: String, value: String?): PersonalDataKind = classify(listOf(columnName), value)

    /**
     * Classifica considerando todos os nomes que identificam a coluna.
     *
     * Uma coluna chega ao cliente por dois nomes: o rótulo escolhido na consulta e a coluna de
     * origem no banco. Decidir só pelo rótulo faz `SELECT num_cpf AS codigo` entregar o documento em
     * claro — o mesmo apelido que já derrubara a máscara de segredo antes. Qualquer um dos nomes que
     * anuncie dado pessoal decide, e o mais específico vence.
     */
    fun classify(columnNames: Collection<String>, value: String?): PersonalDataKind {
        val declared = columnNames
            .asSequence()
            .filter { it.isNotBlank() }
            .filterNot { isBooleanFlag(it) }
            .mapNotNull { name ->
                val normalized = name.lowercase(Locale.ROOT)
                NAME_PATTERNS.firstOrNull { (fragments, _) -> fragments.any { matches(normalized, it) } }
            }
            .minByOrNull { NAME_PATTERNS.indexOf(it) }
            ?.second
            ?: return PersonalDataKind.NONE

        if (value.isNullOrBlank()) {
            return declared
        }
        return if (matchesFormat(declared, value)) declared else fallbackFor(declared)
    }

    /**
     * Aplica a janela da categoria. Categoria [PersonalDataKind.NONE] devolve o valor intacto.
     *
     * @param derived verdadeiro quando o valor é resultado de uma expressão sobre a coluna, e não a
     *   coluna em si. Nesse caso a janela posicional não protege: `substr(email, 5, 6)` devolveria
     *   um pedaço real que a janela do valor inteiro esconderia, e iterar o recorte reconstrói o
     *   documento. Valor derivado é escondido por inteiro.
     */
    fun obfuscate(kind: PersonalDataKind, value: String?, derived: Boolean = false): String? {
        if (value == null || value.isBlank() || kind == PersonalDataKind.NONE) {
            return value
        }
        if (derived) {
            return hideCompletely(value)
        }
        return when (kind) {
            PersonalDataKind.CPF -> digitWindow(value, lead = 3, trail = 3, checkDigits = 2)
            PersonalDataKind.CNPJ -> digitWindow(value, lead = 3, trail = 3, checkDigits = 2)
            PersonalDataKind.NATIONAL_ID -> digitWindow(value, lead = 3, trail = 3, checkDigits = 1)
            PersonalDataKind.REGISTRY_NUMBER -> digitWindow(value, lead = 2, trail = 2, checkDigits = 0)
            PersonalDataKind.PHONE -> phone(value)
            PersonalDataKind.NAME -> name(value)
            PersonalDataKind.EMAIL -> email(value)
            PersonalDataKind.BIRTH_DATE -> birthDate(value)
            PersonalDataKind.BANK_ACCOUNT -> digitWindow(value, lead = 0, trail = 4, checkDigits = 0)
            PersonalDataKind.SENSITIVE_ATTRIBUTE -> hideCompletely(value)
            PersonalDataKind.FREE_TEXT -> hideCompletely(value)
            PersonalDataKind.NONE -> value
        }
    }

    /**
     * Mantém [lead] dígitos no começo e [trail] no fim, descontando [checkDigits] verificadores do
     * fim antes de posicionar a janela final.
     *
     * Caracteres que não são dígito são preservados: a forma do documento continua legível. Valor
     * curto demais para acomodar as duas janelas é escondido por inteiro.
     */
    private fun digitWindow(value: String, lead: Int, trail: Int, checkDigits: Int): String {
        val digits = value.count(Char::isDigit)
        val base = digits - checkDigits
        if (base <= 0 || lead + trail >= base) {
            return hideCompletely(value)
        }

        val builder = StringBuilder(value.length)
        var seen = 0
        for (char in value) {
            if (!char.isDigit()) {
                builder.append(char)
                continue
            }
            val visible = seen < lead || (seen >= base - trail && seen < base)
            builder.append(if (visible) char else HIDDEN)
            seen++
        }
        return builder.toString()
    }

    /** Preserva o DDD e os quatro dígitos finais: a região continua analisável, a linha não. */
    private fun phone(value: String): String {
        val digits = value.count(Char::isDigit)
        if (digits < MIN_PHONE_DIGITS) {
            return digitWindow(value, lead = 0, trail = 2, checkDigits = 0)
        }
        val lead = if (digits >= LOCAL_PHONE_DIGITS + AREA_CODE_DIGITS) AREA_CODE_DIGITS else 0
        return digitWindow(value, lead = lead, trail = 4, checkDigits = 0)
    }

    /** Preserva o primeiro nome e reduz os demais à inicial. */
    private fun name(value: String): String {
        val parts = value.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (parts.size <= 1) {
            // A estratégia preserva o primeiro nome; um campo com uma palavra só já é o primeiro nome.
            return parts.firstOrNull() ?: hideCompletely(value)
        }
        val rest = parts.drop(1).joinToString(" ") { part ->
            val initial = part.firstOrNull { it.isLetter() }
            if (initial == null) HIDDEN.toString() else "${initial.uppercaseChar()}."
        }
        return "${parts.first()} $rest"
    }

    /** Preserva o domínio, que descreve a organização, e reduz a parte local a dois caracteres. */
    private fun email(value: String): String {
        val at = value.lastIndexOf('@')
        if (at <= 0) {
            return hideCompletely(value)
        }
        val local = value.substring(0, at)
        val domain = value.substring(at)
        val visible = local.take(EMAIL_LOCAL_VISIBLE)
        return visible + HIDDEN.toString().repeat((local.length - visible.length).coerceAtLeast(1)) + domain
    }

    /** Preserva o ano: faixa etária e regra por idade continuam analisáveis; dia e mês são escondidos. */
    private fun birthDate(value: String): String {
        val year = YEAR_PREFIX.find(value) ?: return hideCompletely(value)

        return value.mapIndexed { index, char ->
            when {
                !char.isDigit() -> char
                index in year.range -> char
                else -> HIDDEN
            }
        }.joinToString("")
    }

    private fun matchesFormat(kind: PersonalDataKind, value: String): Boolean {
        val digits = value.count(Char::isDigit)
        return when (kind) {
            PersonalDataKind.CPF -> digits == CPF_DIGITS
            PersonalDataKind.CNPJ -> digits == CNPJ_DIGITS
            PersonalDataKind.NATIONAL_ID -> digits in NATIONAL_ID_DIGITS
            PersonalDataKind.PHONE -> digits in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS
            PersonalDataKind.EMAIL -> value.contains('@')
            PersonalDataKind.BIRTH_DATE -> YEAR_PREFIX.containsMatchIn(value)
            PersonalDataKind.NAME -> value.any(Char::isLetter)
            PersonalDataKind.BANK_ACCOUNT -> digits > 0
            PersonalDataKind.SENSITIVE_ATTRIBUTE -> true
            PersonalDataKind.FREE_TEXT -> true
            PersonalDataKind.REGISTRY_NUMBER -> true
            PersonalDataKind.NONE -> true
        }
    }

    /**
     * Casa o fragmento contra o nome da coluna.
     *
     * Fragmento curto casa apenas como segmento inteiro, entre separadores: `rg` como substring
     * transforma `isn_orgao_rg` e `isn_orgao_origem` em documento, porque `orgao` contém `rg`.
     * Fragmento longo é específico o bastante para casar em qualquer posição.
     */
    private fun matches(columnName: String, fragment: String): Boolean =
        if (fragment.length > SHORT_FRAGMENT) {
            columnName.contains(fragment)
        } else {
            columnName.split(*SEPARATORS).any { it == fragment }
        }

    /**
     * Coluna booleana nunca carrega documento.
     *
     * `flg_utilizar_nome_social` guarda um indicador, não o nome social de ninguém.
     */
    private fun isBooleanFlag(columnName: String): Boolean =
        BOOLEAN_PREFIXES.any { columnName.lowercase(Locale.ROOT).startsWith(it) }

    /** Quando o formato não confirma o nome, cai na janela mais restritiva em vez de liberar. */
    private fun fallbackFor(kind: PersonalDataKind): PersonalDataKind = when (kind) {
        PersonalDataKind.NAME, PersonalDataKind.EMAIL -> kind
        else -> PersonalDataKind.REGISTRY_NUMBER
    }

    /**
     * Esconde o valor por um marcador de tamanho fixo.
     *
     * É o desfecho de todo caso em que a janela posicional não se aplica: valor sem o formato
     * esperado, curto demais para acomodar a janela, ou derivado de uma expressão.
     *
     * O marcador não acompanha o comprimento do valor de propósito. Repetir um asterisco por
     * caractere entrega o comprimento, que é canal lateral: distingue registros, restringe o espaço
     * de busca e, sobre uma expressão escolhida por quem consulta, devolve o resultado que a máscara
     * deveria esconder.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun hideCompletely(value: String): String = HIDDEN_VALUE

    private const val SHORT_FRAGMENT = 3
    private val SEPARATORS = charArrayOf('_', '-', '.', ' ')
    private val BOOLEAN_PREFIXES = listOf("flg_", "is_", "has_", "ind_", "bol_")

    private const val CPF_DIGITS = 11
    private const val CNPJ_DIGITS = 14
    private const val AREA_CODE_DIGITS = 2
    private const val LOCAL_PHONE_DIGITS = 8
    private const val MIN_PHONE_DIGITS = 8
    private const val MAX_PHONE_DIGITS = 13
    private const val EMAIL_LOCAL_VISIBLE = 2
    private val NATIONAL_ID_DIGITS = 11..15

    private val WHITESPACE = Regex("\\s+")
    private val YEAR_PREFIX = Regex("(19|20)\\d{2}")

    /**
     * Fragmento de nome de coluna que anuncia a categoria, na ordem em que é testado.
     *
     * A ordem importa: `cpf` precede `cnpj`, e os documentos precedem o número genérico, para que a
     * janela mais informativa vença quando dois fragmentos casam.
     */
    private val NAME_PATTERNS: List<Pair<List<String>, PersonalDataKind>> = listOf(
        listOf("cpf") to PersonalDataKind.CPF,
        listOf("cnpj") to PersonalDataKind.CNPJ,
        listOf("pis", "pasep", "nit", "cns", "cartao_sus") to PersonalDataKind.NATIONAL_ID,
        listOf("titulo_eleitor", "titulo_eleitoral", "cnh", "habilitacao", "reservista", "passaporte")
            to PersonalDataKind.NATIONAL_ID,
        listOf("rg", "identidade", "registro_geral") to PersonalDataKind.REGISTRY_NUMBER,
        listOf("email", "e_mail") to PersonalDataKind.EMAIL,
        listOf("telefone", "celular", "fone", "phone", "whatsapp") to PersonalDataKind.PHONE,
        listOf("nascimento", "data_nasc", "dat_nasc", "birth") to PersonalDataKind.BIRTH_DATE,
        listOf("conta_corrente", "num_conta", "conta_bancaria", "nu_conta") to PersonalDataKind.BANK_ACCOUNT,
        listOf(
            "nome_mae", "nome_pai", "nome_completo", "nom_funcionario", "nome_social", "txt_nome",
            "nm_pessoa", "nome_servidor", "nome_pessoa", "nome_civil", "nome_beneficiario",
            "nome_dependente", "nome_titular",
        ) to PersonalDataKind.NAME,
        listOf(
            "raca", "cor_pele", "etnia", "deficiencia", "doenca", "cid", "religiao",
            "orientacao_sexual", "biometria", "digital",
        ) to PersonalDataKind.SENSITIVE_ATTRIBUTE,
        listOf(
            "carteira_profissional", "documento_militar", "reservista", "registro_nacional_estrangeiro",
            "rne", "id_funcional", "matricula_funcional",
        ) to PersonalDataKind.REGISTRY_NUMBER,
        listOf("observacao", "obs_livre", "anotacao", "descricao_pessoal") to PersonalDataKind.FREE_TEXT,
    )
}
