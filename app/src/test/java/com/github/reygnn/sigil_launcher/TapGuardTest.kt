package com.github.reygnn.sigil_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TapGuard.stillValid] — the pure staleness test behind the
 * item-click and long-press guards. No Android runtime: the arithmetic is total and
 * takes only two ints, so it runs on the plain JVM.
 *
 * The scenarios are named after the real gesture timing they stand in for; see
 * [TapGuard] and the guard call sites in MainActivity for the wiring.
 */
class TapGuardTest {

    @Test fun `same generation passes - list unchanged since the finger went down`() {
        assertTrue(TapGuard.stillValid(downGeneration = 7, shownGeneration = 7))
    }

    @Test fun `bumped generation vetoes - list replaced mid-gesture`() {
        // The list was reassigned once (or many times) since ACTION_DOWN: the row at the
        // aimed-at position is no longer the one the user touched.
        assertFalse(TapGuard.stillValid(downGeneration = 7, shownGeneration = 8))
        assertFalse(TapGuard.stillValid(downGeneration = 7, shownGeneration = 99))
    }

    @Test fun `an out-of-order pair also vetoes - the guard is strict equality, not a comparison`() {
        // shownGeneration only ever grows, so down > shown does not arise in practice; the
        // point is that the guard is strict EQUALITY in BOTH directions. The down < shown
        // test above already rules out a `<=` slip; this rules out a `>=` one, which would
        // otherwise let a (hypothetical) down-ahead-of-shown gesture through unnoticed.
        assertFalse(TapGuard.stillValid(downGeneration = 8, shownGeneration = 7))
    }

    @Test fun `NO_TOUCH passes for any current generation - accessibility or hardware click`() {
        // An action invoked directly, with no MotionEvent, carries no generation to
        // compare and must never be vetoed, whatever the list is currently at.
        assertTrue(TapGuard.stillValid(downGeneration = TapGuard.NO_TOUCH, shownGeneration = 0))
        assertTrue(TapGuard.stillValid(downGeneration = TapGuard.NO_TOUCH, shownGeneration = 42))
    }

    @Test fun `NO_TOUCH can never collide with a real generation`() {
        // The counter starts at 0 and only increments, so shownGeneration is never -1.
        // A down-generation of NO_TOUCH therefore only ever means "no touch", never a
        // list that happens to sit at -1 — the sentinel is unambiguous.
        val firstGeneration = 0
        assertFalse(firstGeneration == TapGuard.NO_TOUCH)
    }

    @Test fun `live compare catches the ACTION_UP-to-delivery window`() {
        // This is the whole point of comparing live rather than snapshotting at UP:
        // at ACTION_UP the list was still at the down-generation (a snapshot would have
        // frozen "valid"), but a background load replaced it before the posted click ran.
        // Comparing against the CURRENT generation at delivery vetoes it correctly.
        val downGeneration = 3          // captured at ACTION_DOWN, preserved past ACTION_UP
        val generationAtUp = 3          // still unchanged when the finger lifted
        val generationAtDelivery = 4    // a background load bumped it before the click ran

        // A UP-time snapshot would have passed...
        assertTrue(downGeneration == generationAtUp)
        // ...but the live compare at delivery vetoes.
        assertFalse(TapGuard.stillValid(downGeneration, generationAtDelivery))
    }
}
