/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

/**
 * Small, dependency-free directed-graph helpers shared by [IrValidator] (cycle rule) and the layout
 * engine (cycle breaking + layering). Operates on opaque string node ids.
 */
object CycleDetector {

    /**
     * Returns a single cycle (as an ordered list of node ids, first == last conceptually) if the
     * directed graph contains one, otherwise null. Uses iterative DFS 3-coloring to avoid deep
     * recursion on large graphs.
     *
     * Determinism: the DFS start order follows the caller-supplied [nodeIds] list, then any ids
     * seen only on edges, via a [LinkedHashSet] (insertion-ordered). This is deliberate — the
     * adjacency [HashMap]'s own key-iteration order is unspecified and, on Kotlin/JS, differs from
     * the JVM, so iterating it directly would make cycle breaking (and therefore the rendered
     * layout and golden tests) platform-dependent. As long as the caller passes a stable [nodeIds]
     * order, the same IR yields the same cycle on every run and platform. See the
     * order-independence regression test.
     */
    fun findCycle(nodeIds: List<String>, edges: List<Pair<String, String>>): List<String>? {
        val adjacency = HashMap<String, MutableList<String>>()
        nodeIds.forEach { adjacency.getOrPut(it) { mutableListOf() } }
        for ((from, to) in edges) {
            if (from == to) return listOf(from, from) // self-loop is a cycle
            adjacency.getOrPut(from) { mutableListOf() }.add(to)
            adjacency.getOrPut(to) { mutableListOf() }
        }

        val WHITE = 0
        val GRAY = 1
        val BLACK = 2
        val color = HashMap<String, Int>().apply { adjacency.keys.forEach { put(it, WHITE) } }
        val parent = HashMap<String, String?>()

        // Iterate in caller-supplied node order (then any edge-only ids) so results are
        // deterministic
        // across runs/platforms — HashMap key order is unspecified on Kotlin/JS and would make the
        // rendered layout (which depends on cycle breaking) non-deterministic, breaking golden
        // tests.
        val order =
            LinkedHashSet<String>().apply {
                addAll(nodeIds)
                addAll(adjacency.keys)
            }
        for (start in order) {
            if (color[start] != WHITE) continue
            // iterative DFS
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(start to 0)
            parent[start] = null
            while (stack.isNotEmpty()) {
                val (node, idx) = stack.removeLast()
                if (idx == 0) color[node] = GRAY
                val neighbors = adjacency[node] ?: emptyList()
                if (idx < neighbors.size) {
                    stack.addLast(node to idx + 1)
                    val next = neighbors[idx]
                    when (color[next]) {
                        GRAY -> return buildCycle(parent, node, next)
                        WHITE -> {
                            parent[next] = node
                            stack.addLast(next to 0)
                        }
                        else -> Unit
                    }
                } else {
                    color[node] = BLACK
                }
            }
        }
        return null
    }

    private fun buildCycle(parent: Map<String, String?>, from: String, to: String): List<String> {
        val path = ArrayList<String>()
        var cur: String? = from
        while (cur != null && cur != to) {
            path.add(cur)
            cur = parent[cur]
        }
        path.add(to)
        path.reverse()
        path.add(to)
        return path
    }

    /**
     * Returns the set of edge indices that, if reversed/removed, make the graph acyclic. Greedy DFS
     * back-edge detection — good enough for layout cycle breaking.
     */
    fun feedbackEdgeSet(nodeIds: List<String>, edges: List<Pair<String, String>>): Set<Int> {
        val adjacency = HashMap<String, MutableList<Pair<String, Int>>>()
        nodeIds.forEach { adjacency.getOrPut(it) { mutableListOf() } }
        edges.forEachIndexed { index, (from, to) ->
            adjacency.getOrPut(from) { mutableListOf() }.add(to to index)
            adjacency.getOrPut(to) { mutableListOf() }
        }
        val WHITE = 0
        val GRAY = 1
        val BLACK = 2
        val color = HashMap<String, Int>().apply { adjacency.keys.forEach { put(it, WHITE) } }
        val feedback = HashSet<Int>()
        val order =
            LinkedHashSet<String>().apply {
                addAll(nodeIds)
                addAll(adjacency.keys)
            }

        fun dfs(start: String) {
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(start to 0)
            while (stack.isNotEmpty()) {
                val (node, idx) = stack.removeLast()
                if (idx == 0) color[node] = GRAY
                val neighbors = adjacency[node] ?: emptyList()
                if (idx < neighbors.size) {
                    stack.addLast(node to idx + 1)
                    val (next, edgeIndex) = neighbors[idx]
                    when (color[next]) {
                        GRAY -> feedback.add(edgeIndex) // back edge
                        WHITE -> stack.addLast(next to 0)
                        else -> Unit
                    }
                } else {
                    color[node] = BLACK
                }
            }
        }
        for (start in order) {
            if (color[start] == WHITE) dfs(start)
        }
        return feedback
    }
}
