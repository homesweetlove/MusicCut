package com.musiccut.core.edit

/** Snapshot-based undo/redo. Projects are immutable, so snapshots are cheap structural shares. */
class UndoStack<T>(private val limit: Int = 200) {
    private val undo = ArrayDeque<Pair<String, T>>()
    private val redo = ArrayDeque<Pair<String, T>>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoLabel: String? get() = undo.lastOrNull()?.first
    val redoLabel: String? get() = redo.lastOrNull()?.first

    /** Records [before] so that the change labelled [label] can be undone. */
    fun push(label: String, before: T) {
        undo.addLast(label to before)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    /** Forgets the most recent entry (e.g. a gesture that ended up changing nothing). */
    fun dropLast() {
        undo.removeLastOrNull()
    }

    fun undo(current: T): T? {
        val (label, prev) = undo.removeLastOrNull() ?: return null
        redo.addLast(label to current)
        return prev
    }

    fun redo(current: T): T? {
        val (label, next) = redo.removeLastOrNull() ?: return null
        undo.addLast(label to current)
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
