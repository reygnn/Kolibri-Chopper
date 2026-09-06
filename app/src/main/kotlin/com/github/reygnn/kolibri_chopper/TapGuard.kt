package com.github.reygnn.kolibri_chopper

/**
 * The pure staleness test behind the item-click and long-press guards, lifted out of
 * [MainActivity] so it can be unit-tested on the JVM without a touch stream or an
 * AbsListView. It owns no state: the Activity holds the mutable generation counters and
 * only delegates this one arithmetic question here.
 *
 * Why the guard exists: AbsListView POSTS its click and long-press. Between the finger
 * going down and the posted action running, a background app-load can replace the shown
 * list (`applyFilter` bumps the generation on every reassignment). AbsListView only drops
 * a pending action while its own `mDataChanged` flag is up, and a single layout pass
 * clears that flag — so the action can still land on whatever row has since moved into the
 * aimed-at slot. Each gesture records the generation the list had when it began; comparing
 * that to the CURRENT generation says whether the row under that position is still the one
 * that was aimed at.
 *
 * `internal`, not `private`, purely so the test source set can reach it — same rationale as
 * [LauncherLogic].
 */
internal object TapGuard {

    /**
     * No touch has been seen for this action — an accessibility service or hardware key
     * invoked it directly, with no MotionEvent. Such an action carries no generation to
     * compare and MUST pass, or the launcher becomes unusable with a screen reader.
     *
     * -1 is safe as the sentinel because the generation counter starts at 0 and only ever
     * increments: a real generation can neither collide with NO_TOUCH nor decrease back
     * onto a stale value.
     */
    const val NO_TOUCH = -1

    /**
     * May an action that began when the list was at [downGeneration] still act on a list
     * now at [shownGeneration]?
     *
     * [NO_TOUCH] passes (no touch to attribute — see the constant). Otherwise the two must
     * match: an unequal pair means the list was replaced mid-gesture, so the row at the
     * aimed-at position is no longer the one the user touched.
     *
     * The comparison is deliberately made against the CURRENT [shownGeneration] at the
     * moment the action is delivered — not a verdict frozen earlier — so a replacement that
     * lands after the finger lifts but before the posted action runs is still caught. The
     * caller decides which field to pass (the live down-generation for a long-press, the
     * down-generation preserved past ACTION_UP for a click); this function only does the
     * arithmetic.
     */
    fun stillValid(downGeneration: Int, shownGeneration: Int): Boolean =
        downGeneration == NO_TOUCH || downGeneration == shownGeneration
}
