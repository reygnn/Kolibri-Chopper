# Kolibri Chopper

A **text-only, terminal-styled Android launcher** — the radical strip-down of
[Kolibri Launcher](../Kolibri_Launcher). The experiment: kick out *everything*
a launcher does not strictly need and see how small the APK gets.

## What it does

- Lists launchable apps in monospace light gray (`#D4D4D4`) on black.
- Enumerates the current user's launchable apps via a `PackageManager`
  MAIN/LAUNCHER query. Single-user only — no work-profile, cloned or
  private-space handling.
- The bottom command line drives everything; the leading character picks a mode:
  - *(empty)* — your favorites (or the full drawer until you set some)
  - `text` — substring search across **all** apps (hidden included)
  - `*` — the full drawer: everything except hidden apps, **but a favorite is
    always kept even when also hidden** (favoriting overrides hiding, so a starred
    app is never trimmed from the default views)
  - `#[text]` — edit hidden: tap a row to toggle `[x]`, saved immediately
  - `![text]` — edit favorites: tap a row to toggle `[x]`, saved immediately
  - `!!` — reorder favorites: tap a row to pick it up (marked `»`), tap another
    row to drop it there; tap the picked row again to cancel. Saved immediately
  - `~` + **Enter** — reload the config from disk
- Tap a row or press **Enter** to launch. **Enter** launches the row nearest the
  command line (the bottom-most, since the list fills upward). This holds even on an
  empty prompt — **Enter** with nothing typed launches your nearest favorite. That
  is intentional: it makes the prompt a one-key quick-launch for your top favorite.
- Long-press any row to set a custom name.
- Registers as `HOME` + `LAUNCHER`, `singleTask`. That's it.

## Which line?

Two lines are maintained; pick whichever fits.

- **0.2.x (this line)** — adds favorites, hidden apps and custom names, stored in
  `chopper.json`. App enumeration is single-user only.
- **0.1.x** — deliberately barebones and staying that way: no config, no
  persistence, just list, filter and launch. It is also the only line that carries
  the `LauncherApps` multi-user listing (work-profile, cloned and private-space
  apps). Choose it from the
  [Releases](https://github.com/reygnn/Kolibri-Chopper/releases) page (latest:
  0.1.2) if you prefer the smaller, stateless launcher — or need cross-profile apps.

## What was chopped

Compared to Kolibri Launcher, the Chopper drops **all** of it:

- no Compose, no Material, **no AndroidX at all** — platform widgets only
- no Hilt / DI — a single `Activity` wires itself
- no Navigation / Fragments — one `Activity` (rename is a bare platform dialog)
- no DataStore / Room / SharedPreferences — the small amount of state (hidden
  apps, favorites, custom names) is a single hand-rolled `chopper.json` in
  `filesDir`, written durably (temp-file + fsync + rename, then a directory
  fsync so the rename itself survives power-loss, not just a crash); the previous
  good version is rotated into a `.bak` that recovery falls back to and heals the
  primary from on the next load; and edited only through the in-app modes above
- no ACRA / Timber — `android.util.Log`
- no ViewBinding / XML layouts — UI built in code

The whole launcher is one file: `app/.../MainActivity.kt`. The only
dependency is the Kotlin stdlib (added automatically by AGP's built-in Kotlin).

## Build

```bash
./gradlew assembleRelease   # unsigned release APK (R8 full mode + resource shrink)
```

Stack matches the family baseline: AGP 9 built-in Kotlin, Gradle 9, JDK 21,
`minSdk = compileSdk = targetSdk = 36` (Android 16, no compat shims).
