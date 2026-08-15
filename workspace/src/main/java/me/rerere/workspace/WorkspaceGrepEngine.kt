package me.rerere.workspace

/**
 * 内容搜索的命令构造与输出解析。
 *
 * 设计要点(对齐 ripgrep/Claude Code GrepTool 的做法, 而不是自己在 JVM 里遍历):
 *
 * 1. **过滤必须发生在遍历层, 不能是事后 filter。** 目录剪枝(--exclude-dir/.gitignore)、路径限制、
 *    文件类型过滤(--include/--type)、二进制检测(-I) 全部交给命令自己做, 这四层是乘法叠加的,
 *    能把候选集从几万文件砍到几十个; 放到 Kotlin 侧 filter 就退化成了加法。
 * 2. **截断发生在返回期, 不是扫描期。** 用 `| head -n` 限制输出行数, 底层始终扫完整个范围,
 *    避免"前 N 条被无关目录吃掉导致真正的命中轮不到"这种假空结果。
 * 3. **输出模式分级。** 默认只回文件名([GrepOutputMode.FILES_WITH_MATCHES]), 模型需要行内容时
 *    再显式要 content, 省 token。
 *
 * 后端优先 ripgrep, 不存在时降级 GNU grep。降级路径同样具备上面四层过滤(除 .gitignore 感知),
 * 性能仍远好于 JVM 手写遍历。
 */
object WorkspaceGrepEngine {

    /** stdout 首行标记实际使用的后端, 便于上层如实回报而不是猜 */
    private const val BACKEND_MARKER = "__RKH_BACKEND__"

    /**
     * 默认剪掉的目录。ripgrep 会自动读 .gitignore, 这里是给 GNU grep 兜底,
     * 同时覆盖那些通常没被 .gitignore 收录却同样该跳过的目录(.git 本身、IDE 缓存等)。
     */
    private val DEFAULT_EXCLUDE_DIRS = listOf(
        ".git", ".hg", ".svn",
        "node_modules", "bower_components", "vendor",
        "build", "dist", "out", "target", "bin", "obj",
        ".gradle", ".idea", ".vscode", ".cxx",
        ".cache", "__pycache__", ".mypy_cache", ".pytest_cache", ".ruff_cache",
        ".venv", "venv", "env", ".tox",
        ".next", ".nuxt", ".svelte-kit", ".terraform",
        "Pods", "DerivedData",
    )

    /** GNU grep 没有 --type, 把常用类型名翻译成 --include glob */
    private val TYPE_GLOBS: Map<String, List<String>> = mapOf(
        "kt" to listOf("*.kt", "*.kts"),
        "kotlin" to listOf("*.kt", "*.kts"),
        "java" to listOf("*.java"),
        "py" to listOf("*.py", "*.pyi"),
        "python" to listOf("*.py", "*.pyi"),
        "js" to listOf("*.js", "*.mjs", "*.cjs", "*.jsx"),
        "ts" to listOf("*.ts", "*.tsx", "*.mts", "*.cts"),
        "go" to listOf("*.go"),
        "rust" to listOf("*.rs"),
        "rs" to listOf("*.rs"),
        "c" to listOf("*.c", "*.h"),
        "cpp" to listOf("*.cpp", "*.cc", "*.cxx", "*.hpp", "*.hh", "*.hxx"),
        "cs" to listOf("*.cs"),
        "swift" to listOf("*.swift"),
        "rb" to listOf("*.rb"),
        "php" to listOf("*.php"),
        "sh" to listOf("*.sh", "*.bash", "*.zsh"),
        "md" to listOf("*.md", "*.markdown"),
        "json" to listOf("*.json"),
        "yaml" to listOf("*.yaml", "*.yml"),
        "yml" to listOf("*.yaml", "*.yml"),
        "toml" to listOf("*.toml"),
        "xml" to listOf("*.xml"),
        "html" to listOf("*.html", "*.htm"),
        "css" to listOf("*.css", "*.scss", "*.sass", "*.less"),
        "sql" to listOf("*.sql"),
        "gradle" to listOf("*.gradle", "*.gradle.kts"),
        "proto" to listOf("*.proto"),
    )

    fun knownTypes(): List<String> = TYPE_GLOBS.keys.sorted()

    /**
     * 生成一次性 shell 脚本: 自己探测后端并执行, 避免为了探测多启一次 proot(启动开销远大于搜索本身)。
     */
    fun buildCommand(request: WorkspaceGrepRequest): String {
        val limit = request.outputLineBudget()
        val rg = renderArgs(ripgrepArgs(request))
        val gnu = renderArgs(gnuGrepArgs(request))
        return buildString {
            append("if command -v rg >/dev/null 2>&1; then ")
            append("printf '%s rg\\n' '$BACKEND_MARKER'; ")
            append("rg $rg | head -n $limit; ")
            append("else ")
            append("printf '%s grep\\n' '$BACKEND_MARKER'; ")
            append("grep $gnu | head -n $limit; ")
            append("fi")
        }
    }

    private fun ripgrepArgs(request: WorkspaceGrepRequest): List<String> = buildList {
        add("--color=never")
        add("--no-messages")
        when (request.outputMode) {
            GrepOutputMode.FILES_WITH_MATCHES -> add("--files-with-matches")
            GrepOutputMode.COUNT -> {
                add("--count")
                add("--with-filename")
            }
            GrepOutputMode.CONTENT -> {
                add("--no-heading")
                add("--line-number")
                // 强制带文件名: 搜索目标是单个文件时默认不输出文件名, 会让解析格式出现两种分支
                add("--with-filename")
                // 文件名与行号之间用 NUL 分隔, 路径里带冒号时才不会把解析搞乱
                add("--null")
                if (request.after > 0) { add("--after-context"); add(request.after.toString()) }
                if (request.before > 0) { add("--before-context"); add(request.before.toString()) }
            }
        }
        if (request.ignoreCase) add("--ignore-case")
        if (request.fixedString) add("--fixed-strings")
        if (request.multiline) { add("--multiline"); add("--multiline-dotall") }
        if (request.hidden) add("--hidden")
        if (!request.followGitignore) add("--no-ignore")
        // 即便 rg 会读 .gitignore, 也补上硬性剪枝: .git 等目录通常不在 .gitignore 里
        DEFAULT_EXCLUDE_DIRS.forEach { dir ->
            add("--glob")
            add("!$dir/")
        }
        request.glob?.takeIf { it.isNotBlank() }?.let { add("--glob"); add(it) }
        request.type?.takeIf { it.isNotBlank() }?.let { add("--type"); add(it) }
        add("--regexp")
        add(request.query)
        add("--")
        add(request.searchPath())
    }

    private fun gnuGrepArgs(request: WorkspaceGrepRequest): List<String> = buildList {
        add("-r")
        // -I: 跳过二进制文件。缺了这条会把 .so/.dex/图片按 UTF-8 硬啃, 单行几十万字符喂给正则
        add("-I")
        add("--color=never")
        add("-s")
        when (request.outputMode) {
            GrepOutputMode.FILES_WITH_MATCHES -> add("-l")
            GrepOutputMode.COUNT -> {
                add("-c")
                // 同样需要 -H: 否则单文件下输出只有个裸数字, 拿不到 path
                add("-H")
            }
            GrepOutputMode.CONTENT -> {
                add("-n")
                // -H: 强制带文件名。搜单个文件时 grep 默认省略文件名, 输出会退化成 `3:text`,
                // 解析时就会把行号误当成路径
                add("-H")
                add("-Z")
                if (request.after > 0) { add("-A"); add(request.after.toString()) }
                if (request.before > 0) { add("-B"); add(request.before.toString()) }
            }
        }
        if (request.ignoreCase) add("-i")
        if (request.fixedString) add("-F") else add("-E")
        DEFAULT_EXCLUDE_DIRS.forEach { add("--exclude-dir=$it") }
        val includes = buildList {
            request.glob?.takeIf { it.isNotBlank() }?.let { add(normalizeGlobForGnu(it)) }
            request.type?.takeIf { it.isNotBlank() }
                ?.let { TYPE_GLOBS[it.lowercase()] }
                ?.let { addAll(it) }
        }
        includes.forEach { add("--include=$it") }
        // GNU grep 里 --exclude 会抵消 --include 的收窄语义(实测 `--exclude='.*' --include='*.kt'`
        // 会把 .md 也搜出来), 所以只在没有 include 时才用它近似实现 "跳过隐藏文件"。
        if (!request.hidden && includes.isEmpty()) {
            add("--exclude=.*")
        }
        add("-e")
        add(request.query)
        add("--")
        add(request.searchPath())
    }

    /**
     * GNU grep 的 --include 只按 basename 匹配, 带 recursive 前缀的 glob 反而匹配不到,
     * 所以把开头的 "递归通配 + 斜杠" 前缀剥掉。
     */
    private fun normalizeGlobForGnu(glob: String): String =
        glob.trim().removePrefix("**/").ifBlank { "*" }

    private fun renderArgs(args: List<String>): String =
        args.joinToString(" ") { shellQuote(it) }

    /** 单引号包裹 + 内部单引号转义, 任何 pattern 都不会被 shell 二次解释 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun parse(request: WorkspaceGrepRequest, stdout: String, stderr: String, exitCode: Int): WorkspaceGrepResult {
        val lines = stdout.split('\n')
        var backend = "unknown"
        var body = lines
        lines.firstOrNull()?.let { first ->
            if (first.startsWith(BACKEND_MARKER)) {
                backend = first.removePrefix(BACKEND_MARKER).trim().ifBlank { "unknown" }
                body = lines.drop(1)
            }
        }
        val payload = body.dropLastWhile { it.isBlank() }

        val budget = request.outputLineBudget()
        // 输出刚好顶到预算说明底层还有更多结果被 head 截掉了
        val hitBudget = payload.size >= budget
        val skip = request.offset.coerceAtLeast(0)
        val take = request.headLimit.coerceAtLeast(1)

        return when (request.outputMode) {
            GrepOutputMode.FILES_WITH_MATCHES -> {
                val all = payload.filter { it.isNotBlank() }
                WorkspaceGrepResult(
                    mode = request.outputMode,
                    backend = backend,
                    files = all.drop(skip).take(take),
                    totalReturned = all.size,
                    truncated = hitBudget || all.size > skip + take,
                    stderr = stderr.trim(),
                    exitCode = exitCode,
                )
            }

            GrepOutputMode.COUNT -> {
                val all = payload.mapNotNull { parseCountLine(it) }.filter { it.count > 0 }
                WorkspaceGrepResult(
                    mode = request.outputMode,
                    backend = backend,
                    counts = all.drop(skip).take(take),
                    totalReturned = all.size,
                    truncated = hitBudget || all.size > skip + take,
                    stderr = stderr.trim(),
                    exitCode = exitCode,
                )
            }

            GrepOutputMode.CONTENT -> {
                val all = payload.mapNotNull { parseContentLine(it) }
                WorkspaceGrepResult(
                    mode = request.outputMode,
                    backend = backend,
                    matches = all.drop(skip).take(take),
                    totalReturned = all.size,
                    truncated = hitBudget || all.size > skip + take,
                    stderr = stderr.trim(),
                    exitCode = exitCode,
                )
            }
        }
    }

    /** `path:count`, 路径本身可能含冒号, 所以从最后一个冒号切 */
    private fun parseCountLine(line: String): WorkspaceGrepCount? {
        if (line.isBlank()) return null
        val idx = line.lastIndexOf(':')
        if (idx <= 0) return null
        val count = line.substring(idx + 1).trim().toIntOrNull() ?: return null
        return WorkspaceGrepCount(path = line.substring(0, idx), count = count)
    }

    /**
     * content 模式输出形如 `path\u0000123:text`(命中行) 或 `path\u0000123-text`(上下文行)。
     * NUL 之后才出现的第一个 `:`/`-` 才是行号分隔符, 这样路径含冒号也能正确解析。
     */
    private fun parseContentLine(line: String): WorkspaceGrepMatch? {
        if (line.isBlank()) return null
        val nul = line.indexOf('\u0000')
        val path: String
        val rest: String
        if (nul >= 0) {
            path = line.substring(0, nul)
            rest = line.substring(nul + 1)
        } else {
            // 没有 NUL(例如某些实现忽略了 --null): 退化按首个冒号切
            val idx = line.indexOf(':')
            if (idx <= 0) return null
            path = line.substring(0, idx)
            rest = line.substring(idx + 1)
        }
        val sep = rest.indexOfFirst { it == ':' || it == '-' }
        if (sep <= 0) return null
        val lineNumber = rest.substring(0, sep).trim().toIntOrNull() ?: return null
        return WorkspaceGrepMatch(
            path = path,
            line = lineNumber,
            text = rest.substring(sep + 1),
            isContext = rest[sep] == '-',
        )
    }
}

enum class GrepOutputMode {
    /** 只回命中行, 最贵, 需要看代码时才用 */
    CONTENT,

    /** 只回文件路径, 默认值, 最省 token */
    FILES_WITH_MATCHES,

    /** 只回每个文件的命中数, 用来估关键词密度 */
    COUNT,
    ;

    companion object {
        fun from(raw: String?): GrepOutputMode = when (raw?.trim()?.lowercase()) {
            "content" -> CONTENT
            "count" -> COUNT
            "files_with_matches", "files", null, "" -> FILES_WITH_MATCHES
            else -> FILES_WITH_MATCHES
        }
    }
}

data class WorkspaceGrepRequest(
    val query: String,
    /** rootfs 内的绝对路径; proot 已把 /workspace 与各外挂目录 bind 进去, 直接用绝对路径即可 */
    val path: String = "/workspace",
    /** true 表示按字面量搜(-F); 默认 false, 即完整正则语法 */
    val fixedString: Boolean = false,
    val ignoreCase: Boolean = true,
    val glob: String? = null,
    val type: String? = null,
    val outputMode: GrepOutputMode = GrepOutputMode.FILES_WITH_MATCHES,
    val before: Int = 0,
    val after: Int = 0,
    val headLimit: Int = DEFAULT_HEAD_LIMIT,
    val offset: Int = 0,
    val multiline: Boolean = false,
    val hidden: Boolean = false,
    val followGitignore: Boolean = true,
) {
    init {
        require(query.isNotBlank()) { "Search query is required" }
    }

    fun searchPath(): String = path.trim().ifBlank { "/workspace" }

    /**
     * 交给 `head -n` 的行数预算。多取一行用于判断是否被截断;
     * content 模式带上下文时一次命中会产出多行, 按上下文倍数放宽。
     */
    fun outputLineBudget(): Int {
        val base = offset.coerceAtLeast(0) + headLimit.coerceAtLeast(1) + 1
        val perMatch = if (outputMode == GrepOutputMode.CONTENT) 1 + before + after else 1
        return (base.toLong() * perMatch.coerceAtLeast(1)).coerceAtMost(MAX_OUTPUT_LINES.toLong()).toInt()
    }

    companion object {
        const val DEFAULT_HEAD_LIMIT = 250
        const val MAX_HEAD_LIMIT = 2_000
        const val MAX_OUTPUT_LINES = 20_000
    }
}

data class WorkspaceGrepMatch(
    val path: String,
    val line: Int,
    val text: String,
    /** true 表示这行是 -A/-B/-C 带出来的上下文, 不是命中行 */
    val isContext: Boolean = false,
)

data class WorkspaceGrepCount(
    val path: String,
    val count: Int,
)

data class WorkspaceGrepResult(
    val mode: GrepOutputMode,
    val backend: String,
    val matches: List<WorkspaceGrepMatch> = emptyList(),
    val files: List<String> = emptyList(),
    val counts: List<WorkspaceGrepCount> = emptyList(),
    val totalReturned: Int = 0,
    val truncated: Boolean = false,
    val stderr: String = "",
    val exitCode: Int = 0,
) {
    fun isEmpty(): Boolean = matches.isEmpty() && files.isEmpty() && counts.isEmpty()
}
