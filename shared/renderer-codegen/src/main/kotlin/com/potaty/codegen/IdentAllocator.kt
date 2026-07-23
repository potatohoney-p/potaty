/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

/**
 * Per-compile allocator mapping free-form IR ids to unique, syntactically-safe identifiers.
 *
 * [LabelEscaper.sanitizeIdent] is many-to-one (e.g. "a.b-c" and "a_b_c" both sanitise to "a_b_c").
 * Using it directly to key nodes/edges would let two DISTINCT ids collapse into one identifier —
 * silently merging nodes and misattributing edges. That is both a correctness bug and a security
 * issue (an attacker-supplied id could be crafted to collide with another node's id).
 *
 * This allocator guarantees, within a single compile:
 *  - **injectivity** — two distinct raw ids never receive the same identifier; collisions on the
 *    sanitised base are disambiguated with a numeric suffix (`_2`, `_3`, ...). The suffix is checked
 *    against ALL previously-allocated identifiers, so even an adversarial id equal to another id's
 *    disambiguated form is itself disambiguated again.
 *  - **consistency** — the same raw id always yields the same identifier (memoised), so a node
 *    declaration and every edge endpoint that references it agree.
 *  - **determinism** — output depends only on the order ids are first seen. Compilers allocate
 *    groups and nodes (in declaration order) before edges, so results are stable and golden-testable;
 *    there is no hashing or hash-map iteration-order dependence.
 *
 * Create exactly one instance per `compile()` (per emitted document).
 */
class IdentAllocator {
    private val byRawId = HashMap<String, String>()
    private val taken = HashSet<String>()

    /**
     * Returns the unique identifier for [rawId], allocating (and disambiguating) on first sight.
     *
     * [rawId] is expected to be non-empty (IrValidator rule IR-R001 enforces non-empty node/edge
     * ids upstream, so compilers never see a blank id in practice). This method is nonetheless total
     * for an empty input: [LabelEscaper.sanitizeIdent] maps `""` to the base `"n_"`, which is then
     * uniquified like any other base (`n_`, `n__2`, ...). It never throws and never returns an empty
     * or syntactically-invalid identifier.
     */
    fun identify(rawId: String): String {
        byRawId[rawId]?.let { return it }
        val base = LabelEscaper.sanitizeIdent(rawId)
        var candidate = base
        var suffix = 2
        // HashSet.add returns false when the element is already present, so this loop advances until
        // it finds (and reserves) a free identifier.
        while (!taken.add(candidate)) {
            candidate = base + "_" + suffix
            suffix++
        }
        byRawId[rawId] = candidate
        return candidate
    }
}
