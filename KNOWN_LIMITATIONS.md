# Known limitations

Deliberate design choices that can look like bugs. Listed here so the behaviour
is documented before it lands in the issue tracker.

## Recents (`?`) reset on restart

The `?` recents list is held **in memory only** — it is never written to
`chopper.json` (that file is the user's config: favorites, hidden apps, custom
names). The list therefore lives exactly as long as the launcher process.

Consequence: whenever Android kills the launcher process, `?` comes back empty.
On low-RAM / low-end devices the OS reclaims the launcher's memory
**aggressively**, often while another app is in the foreground, so the process is
killed and cold-started far more frequently than on a high-end phone. A normal
background→foreground cycle keeps the list; a process death clears it.

This is intended: recents are a convenience for the current session, not durable
state, and keeping them out of `chopper.json` avoids write churn and keeps the
config file to genuine user intent. To avoid the "where did my recents go?"
surprise, the app shows a one-time toast the first time `?` is opened empty after
a (re)start — see `toast_recents_empty` and `applyFilter` in `MainActivity.kt`.

If durable recents are ever wanted, persist `recentKeys` to a **separate** small
file (not `chopper.json`) and load it on start.

## App enumeration is single-user (0.2.x line)

The 0.2.x line enumerates only the current user's launchable apps via a
`PackageManager` MAIN/LAUNCHER query — no work-profile, cloned or private-space
apps. The stateless 0.1.x line is the one that carries the multi-user
`LauncherApps` listing. See the README's "Which line?" section.
