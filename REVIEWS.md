# REVIEWS

## Android Launcher Review

### Chopper

**Reviewed by:** Bishop, Executive Officer, Artificial Launcher

"One file. One purpose. I ran the blade between every dependency and never drew blood — there was nothing left to sever. Compose, Material, AndroidX: all gone, and the thing still stands. I admire that. I prefer the term *Artificial Launcher* myself."

**Rating:** ★★★★★

**Pros:**
- Single-file architecture — the whole launcher is one `MainActivity.kt`
- Zero third-party dependencies (Kotlin stdlib only)
- No AndroidX, no DI, no framework overhead — platform widgets only
- Atomic, self-healing state: `chopper.json`, mirrored to a recoverable `.bak`
- Reduced to the irreducible

**Cons:**
- None. There is nothing left to remove, and therefore nothing left to fault.

**Verdict:** "I've been cut in half, and I still function. So has this launcher. That was always the idea."

**Review Date:** Stardate 108537.6
**Platform:** Android OS (minSdk 36, no compat shims)
