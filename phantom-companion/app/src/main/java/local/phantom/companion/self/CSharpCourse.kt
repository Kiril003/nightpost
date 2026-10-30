package local.phantom.companion.self

/**
 * Перший курс PHANTOM: основи C#. Питання мають одну відповідь, яку можна
 * звірити без моделі; задачі перевіряються читанням коду — запускати C# на
 * телефоні нема чим.
 */
object CSharpCourse {

    data class Question(val code: String, val answer: String, val explain: String)

    data class Need(val rx: Regex, val what: String)

    data class Topic(
        val id: String,
        val name: Line,
        val task: Line,
        val needs: List<Need>,
        val review: Question,
    )

    private fun rx(p: String) = Regex(p)

    val VARS = Topic(
        id = "vars",
        name = Line.TOPIC_VARS,
        task = Line.TASK_VARS,
        needs = listOf(
            Need(rx("int\\s+age\\s*=\\s*30\\s*;"), "int age = 30;"),
            Need(rx("Console\\s*\\.\\s*WriteLine\\s*\\(\\s*age\\s*\\)"), "Console.WriteLine(age)"),
        ),
        review = Question("int a = 7;\nint b = a / 2;\nConsole.WriteLine(b);", "3", "7 / 2 = 3"),
    )

    val COND = Topic(
        id = "cond",
        name = Line.TOPIC_COND,
        task = Line.TASK_COND,
        needs = listOf(
            Need(rx("if\\s*\\(\\s*n\\s*>\\s*10\\s*\\)"), "if (n > 10)"),
            Need(rx("else"), "else"),
            Need(rx("\"багато\"|\"many\""), "\"багато\""),
            Need(rx("\"мало\"|\"few\""), "\"мало\""),
        ),
        review = Question(
            "int n = 4;\nif (n % 2 == 0) Console.WriteLine(\"even\");\nelse Console.WriteLine(\"odd\");",
            "even",
            "4 % 2 == 0",
        ),
    )

    val LOOPS = Topic(
        id = "loops",
        name = Line.TOPIC_LOOPS,
        task = Line.TASK_LOOPS,
        needs = listOf(
            Need(rx("for\\s*\\("), "for (…)"),
            Need(rx("<=\\s*5|<\\s*6"), "i <= 5"),
            Need(rx("Console\\s*\\.\\s*WriteLine\\s*\\("), "Console.WriteLine(…)"),
        ),
        review = Question("for (int i = 0; i < 3; i++)\n    Console.Write(i);", "012", "0, 1, 2"),
    )

    val METHODS = Topic(
        id = "methods",
        name = Line.TOPIC_METHODS,
        task = Line.TASK_METHODS,
        needs = listOf(
            Need(rx("int\\s+Square\\s*\\(\\s*int\\s+\\w+\\s*\\)"), "int Square(int x)"),
            Need(rx("return\\s+(\\w+)\\s*\\*\\s*\\1|=>\\s*(\\w+)\\s*\\*\\s*\\2"), "return x * x;"),
        ),
        review = Question("static int Twice(int v) => v * 2;\nConsole.WriteLine(Twice(6));", "12", "6 * 2 = 12"),
    )

    val ARRAYS = Topic(
        id = "arrays",
        name = Line.TOPIC_ARRAYS,
        task = Line.TASK_ARRAYS,
        needs = listOf(
            Need(rx("int\\s*\\[\\s*\\]\\s*\\w+\\s*="), "int[] a = …"),
            Need(rx("\\.\\s*Length"), ".Length"),
        ),
        review = Question("int[] a = { 4, 5, 6 };\nConsole.WriteLine(a[1]);", "5", "a[0] = 4, a[1] = 5"),
    )

    val TOPICS = listOf(VARS, COND, LOOPS, METHODS, ARRAYS)

    fun topic(id: String?): Topic? = TOPICS.firstOrNull { it.id == id }

    /** Два перші питання: чи людина читає змінні й цикли. */
    val DIAGNOSTIC = listOf(
        VARS to Question("int x = 5;\nx += 3;\nConsole.WriteLine(x * 2);", "16", "x = 8, 8 * 2 = 16"),
        LOOPS to Question("int s = 0;\nfor (int i = 1; i <= 4; i++) s += i;\nConsole.WriteLine(s);", "10", "1 + 2 + 3 + 4 = 10"),
    )

    fun answered(expected: String, given: String): Boolean {
        val g = given.lowercase().trim()
        if (g.isEmpty()) return false
        val e = expected.lowercase()
        if (g.filterNot { it.isWhitespace() || it == ',' || it == '"' || it == '«' || it == '»' } == e) return true
        return g.split(Regex("[^\\p{L}\\p{N}]+")).any { it == e }
    }

    fun looksLikeCode(text: String): Boolean =
        text.contains(';') || text.contains('{') || Regex("\\w+\\s*\\(.*\\)").containsMatchIn(text)

    /** Що не так у коді — порожньо, коли зауважень нема. */
    fun check(topic: Topic, code: String): List<Say> {
        val issues = ArrayList<Say>()
        val bare = stripStrings(code)
        if (bare.count { it == '{' } != bare.count { it == '}' }) issues += Say(Line.ISSUE_BRACES)
        if (bare.count { it == '(' } != bare.count { it == ')' }) issues += Say(Line.ISSUE_PARENS)
        if (code.count { it == '"' } % 2 != 0) issues += Say(Line.ISSUE_QUOTES)
        if (missingSemicolon(bare)) issues += Say(Line.ISSUE_SEMICOLON)
        topic.needs.filterNot { it.rx.containsMatchIn(code) }.forEach { issues += Say(Line.ISSUE_NEED, it.what) }
        return issues
    }

    private fun stripStrings(code: String): String = code.replace(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), "\"\"")

    private val STATEMENT = Regex("^(?:int|var|string|double|bool|Console|return|[a-zA-Z_]\\w*\\s*(?:=|\\+=|-=|\\+\\+|--))")
    private val BLOCK = Regex("^(?:for|foreach|while|if|else|switch|do|using|namespace|class)(?:\\W|$)")
    private val HEADER = Regex("^(?:(?:static|public|private|protected|internal|override|async)\\s+)*[\\w<>\\[\\]]+\\s+\\w+\\s*\\(")

    private fun missingSemicolon(code: String): Boolean = code.lines().map { it.trim() }.any { line ->
        line.isNotEmpty() && STATEMENT.containsMatchIn(line) && !BLOCK.containsMatchIn(line) &&
            !HEADER.containsMatchIn(line) && !line.contains("=>") &&
            !line.endsWith(";") && !line.endsWith("{") && !line.endsWith("}") && !line.endsWith(",")
    }
}
