# Kolibri Chopper

A **text-only, terminal-styled Android launcher** — the radical strip-down of
[Kolibri Launcher](../Kolibri_Launcher). The experiment: kick out *everything*
a launcher does not strictly need and see how small the APK gets.

## What it does

- Lists every launchable app in monospace light gray (`#D4D4D4`) on black.
- Enumerates apps across profiles via `LauncherApps` — work-profile, cloned and
  private-space copies show up too and launch into their own profile. Work-profile
  entries are badged (e.g. *Work Gmail*) so they aren't identical rows.
- Type in the bottom command line to filter; tap or press **Enter** to launch.
- Registers as `HOME` + `LAUNCHER`, `singleTask`. That's it.

## What was chopped

Compared to Kolibri Launcher, the Chopper drops **all** of it:

- no Compose, no Material, **no AndroidX at all** — platform widgets only
- no Hilt / DI — a single `Activity` wires itself
- no Navigation / Fragments — one screen
- no DataStore / persistence — no favorites, no settings
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
