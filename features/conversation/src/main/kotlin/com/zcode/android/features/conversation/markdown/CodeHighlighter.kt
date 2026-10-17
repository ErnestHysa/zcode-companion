package com.zcode.android.features.conversation.markdown

/**
 * H1.3 (gaps-and-polish Phase 3C): bounded syntax highlighting for fenced code — the
 * phone's answer to the desktop's Shiki grammar set, sized to INV-5: one single-pass
 * tokenizer over keyword / string / comment / number classes for the languages a
 * coding agent's replies actually carry. No grammar engine, no dependency.
 *
 * Contract: total on any input (never throws), and the tokens round-trip the source
 * exactly (every character appears once, in order) — the renderer only colors.
 * Unknown or absent languages yield one PLAIN token.
 */
object CodeHighlighter {

    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER }

    data class Token(val text: String, val kind: Kind)

    private class Grammar(
        val keywords: Set<String>,
        val lineComment: List<String>,
        val blockComment: Pair<String, String>?,
        val stringQuotes: List<String>,
    )

    private val C_LIKE_KEYWORDS = setOf(
        "fun", "val", "var", "class", "interface", "object", "return", "if", "else", "when", "for",
        "while", "do", "in", "is", "as", "null", "true", "false", "this", "super", "import", "package",
        "private", "public", "internal", "protected", "override", "open", "abstract", "data", "sealed",
        "suspend", "try", "catch", "finally", "throw", "new", "function", "const", "let", "export",
        "default", "extends", "implements", "static", "void", "int", "long", "float", "double",
        "boolean", "char", "byte", "short", "string", "number", "type", "enum", "struct", "impl",
        "trait", "pub", "mut", "match", "use", "mod", "crate", "self", "async", "await", "yield",
        "switch", "case", "break", "continue", "typeof", "instanceof", "where", "by", "lateinit",
        "companion", "init", "constructor", "get", "set", "typealias", "inline", "operator", "infix",
        "go", "defer", "chan", "range", "map", "func", "guard", "defer", "throws", "nil",
    )
    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "with",
        "try", "except", "finally", "lambda", "None", "True", "False", "and", "or", "not", "in", "is",
        "pass", "break", "continue", "yield", "async", "await", "raise", "global", "nonlocal", "del",
        "assert", "self",
    )
    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac",
        "function", "return", "export", "local", "readonly", "select", "time", "set", "unset",
    )
    private val JSON_KEYWORDS = setOf("true", "false", "null")
    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "join", "left", "right", "inner", "outer", "on", "group", "by",
        "order", "limit", "offset", "and", "or", "not", "null", "as", "distinct", "having", "union",
        "primary", "key", "index", "begin", "commit", "rollback", "with",
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
        "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY",
        "ORDER", "LIMIT", "OFFSET", "AND", "OR", "NOT", "NULL", "AS", "DISTINCT", "HAVING", "UNION",
        "PRIMARY", "KEY", "INDEX", "BEGIN", "COMMIT", "ROLLBACK", "WITH",
    )

    private val C_LIKE = Grammar(C_LIKE_KEYWORDS, listOf("//"), "/*" to "*/", listOf("\"", "'", "`"))
    private val PYTHON = Grammar(PYTHON_KEYWORDS, listOf("#"), null, listOf("\"\"\"", "'''", "\"", "'"))
    private val SHELL = Grammar(SHELL_KEYWORDS, listOf("#"), null, listOf("\"", "'"))
    private val JSON = Grammar(JSON_KEYWORDS, emptyList(), null, listOf("\""))
    private val YAML = Grammar(JSON_KEYWORDS, listOf("#"), null, listOf("\"", "'"))
    private val SQL = Grammar(SQL_KEYWORDS, listOf("--"), "/*" to "*/", listOf("'", "\""))

    private val GRAMMARS: Map<String, Grammar> = buildMap {
        for (l in listOf("kotlin", "kt", "kts", "java", "js", "javascript", "ts", "typescript", "jsx", "tsx",
            "swift", "go", "golang", "rust", "rs", "c", "cpp", "c++", "h", "hpp", "cs", "csharp", "dart",
            "scala", "php", "groovy", "gradle", "objc", "objective-c", "css", "scss")) put(l, C_LIKE)
        for (l in listOf("python", "py", "ruby", "rb", "perl", "pl", "r")) put(l, PYTHON)
        for (l in listOf("bash", "sh", "shell", "zsh", "fish", "console", "shell-session", "dockerfile", "makefile", "make", "powershell", "ps1")) put(l, SHELL)
        for (l in listOf("json", "jsonc", "json5")) put(l, JSON)
        for (l in listOf("yaml", "yml", "toml", "ini", "properties", "env")) put(l, YAML)
        for (l in listOf("sql", "mysql", "postgres", "postgresql", "sqlite")) put(l, SQL)
    }

    fun tokenize(lang: String?, code: String): List<Token> {
        if (code.isEmpty()) return emptyList()
        val grammar = lang?.trim()?.lowercase()?.let(GRAMMARS::get) ?: return listOf(Token(code, Kind.PLAIN))
        return runCatching { scan(grammar, code) }.getOrElse { listOf(Token(code, Kind.PLAIN)) }
    }

    private fun scan(g: Grammar, src: String): List<Token> {
        val out = ArrayList<Token>()
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(Token(plain.toString(), Kind.PLAIN))
                plain.clear()
            }
        }
        var i = 0
        val n = src.length
        while (i < n) {
            val ch = src[i]
            // Block comment.
            val bc = g.blockComment
            if (bc != null && src.startsWith(bc.first, i)) {
                val end = src.indexOf(bc.second, i + bc.first.length)
                val stop = if (end < 0) n else end + bc.second.length
                flushPlain()
                out.add(Token(src.substring(i, stop), Kind.COMMENT))
                i = stop
                continue
            }
            // Line comment.
            val lc = g.lineComment.firstOrNull { src.startsWith(it, i) }
            if (lc != null) {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                flushPlain()
                out.add(Token(src.substring(i, end), Kind.COMMENT))
                i = end
                continue
            }
            // String (longest quote first — triple quotes before single).
            val q = g.stringQuotes.firstOrNull { src.startsWith(it, i) }
            if (q != null) {
                var j = i + q.length
                var closed = false
                while (j < n) {
                    if (src[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (src.startsWith(q, j)) {
                        j += q.length
                        closed = true
                        break
                    }
                    if (q.length == 1 && src[j] == '\n') break // single-quote strings end at the line
                    j++
                }
                val stop = if (closed) j else minOf(j, n)
                flushPlain()
                out.add(Token(src.substring(i, stop), Kind.STRING))
                i = stop
                continue
            }
            // Number.
            if (ch.isDigit() && (i == 0 || !isWordChar(src[i - 1]))) {
                var j = i + 1
                while (j < n && (src[j].isLetterOrDigit() || src[j] == '.' || src[j] == '_')) j++
                flushPlain()
                out.add(Token(src.substring(i, j), Kind.NUMBER))
                i = j
                continue
            }
            // Identifier / keyword.
            if (isWordStart(ch)) {
                var j = i + 1
                while (j < n && isWordChar(src[j])) j++
                val word = src.substring(i, j)
                if (word in g.keywords) {
                    flushPlain()
                    out.add(Token(word, Kind.KEYWORD))
                } else {
                    plain.append(word)
                }
                i = j
                continue
            }
            plain.append(ch)
            i++
        }
        flushPlain()
        return out
    }

    private fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
