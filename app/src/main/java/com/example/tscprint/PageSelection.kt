package com.example.tscprint

/** One source of truth for page selection in the preview screen. Pages exposed to the UI are 1-based. */
class PageSelection {

    enum class Mode { ALL, RANGE, MANUAL }

    var mode: Mode = Mode.ALL
        private set

    private var totalPages: Int = 0
    private val selected = linkedSetOf<Int>()

    fun reset(total: Int) {
        totalPages = total.coerceAtLeast(0)
        selectAll()
    }

    fun restore(total: Int, savedMode: String?, savedPages: IntArray): Boolean {
        totalPages = total.coerceAtLeast(0)
        val validPages = savedPages.filter { it in 0 until totalPages }.toSet()
        if (validPages.isEmpty() && totalPages > 0 && savedMode != Mode.MANUAL.name) {
            selectAll()
            return false
        }
        selected.clear()
        selected.addAll(validPages)
        mode = runCatching { Mode.valueOf(savedMode.orEmpty()) }.getOrDefault(Mode.MANUAL)
        if (selected.size == totalPages) mode = Mode.ALL
        return true
    }

    fun selectAll() {
        selected.clear()
        selected.addAll(0 until totalPages)
        mode = Mode.ALL
    }

    fun applyRange(expression: String): Result<Unit> {
        val result = parse(expression)
            ?: return Result.failure(IllegalArgumentException("Invalid page range"))
        selected.clear()
        selected.addAll(result)
        mode = Mode.RANGE
        return Result.success(Unit)
    }

    fun toggle(index: Int) {
        if (index !in 0 until totalPages) return
        if (!selected.add(index)) selected.remove(index)
        mode = if (selected.size == totalPages) Mode.ALL else Mode.MANUAL
    }

    fun isSelected(index: Int): Boolean = index in selected

    fun selectedPages(): Set<Int> = selected.toSet()

    fun count(): Int = selected.size

    fun total(): Int = totalPages

    fun expression(): String = selected.sorted().joinToString(",") { (it + 1).toString() }

    private fun parse(expression: String): Set<Int>? {
        val result = linkedSetOf<Int>()
        val tokens = expression.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (token in tokens) {
            val parts = token.split('-').map { it.trim() }
            if (parts.size > 2 || parts.any { it.toIntOrNull() == null }) return null
            val first = parts[0].toInt()
            val last = if (parts.size == 2) parts[1].toInt() else first
            if (first < 1 || last < first || last > totalPages) return null
            for (page in first..last) result.add(page - 1)
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
