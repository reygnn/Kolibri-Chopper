# Ideen-Parkplatz

Notierte Konzepte, die (noch) nicht gebaut sind. Kein Commitment, nur festgehalten,
damit nichts verloren geht.

---

## ASCII-Wallpaper (0.3-Linie)

**Status:** Plan, noch nicht gebaut. Eigene 0.3-Linie (zweigt von `main`/0.2 ab).

Ein statischer, AMOLED-lastiger ASCII-Backdrop hinter der App-Liste. 0.2 ist radikal
kahl ("alles wegchoppen"); ein Wallpaper ist Deko und bricht diesen Grundsatz —
daher eine eigene 0.3-Linie ("der Chopper mit Vibe"). Bleibt aber treu zum
"no dependencies"-Ethos: reines Kotlin/`Canvas`, kein XML, keine Libs.

### Gewählte Ausprägung
- **Statisch** — einmal zeichnen, kein Render-Loop, praktisch kein Akku.
- **AMOLED-lastig** — Hintergrund echtes Schwarz `#000000` (Pixel aus), Art in der
  fixen Schriftfarbe `#D4D4D4`, sparse Line-Art (viel Schwarz = wenig leuchtende
  Pixel).
- **Vollbild, gedimmt** — Art über den ganzen Screen mit niedrigem Alpha hinter der
  App-Liste; die Labels in voller Helligkeit poppen davor raus.
- **Built-in-Motive** — eine handkuratierte Handvoll, per Command umschaltbar.

### Plan

1. **Branch & Version:** `0.3.x` von `main`, Version `0.3.0`. Erbt
   `ChopperConfig`/`ConfigJson`/das `~`-Command-Muster.

2. **Layering (einziger struktureller Eingriff):** Content in ein `FrameLayout`
   wrappen:
   ```
   FrameLayout (setContentView)
   ├── AsciiWallpaperView  (match_parent, EDGE-TO-EDGE, kein Inset-Padding)
   └── root: LinearLayout   (Background TRANSPARENT statt schwarz)
       ├── ListView         (transparent — ist es schon)
       └── prompt EditText  (transparent — ist es schon)
   ```
   Das echte Schwarz wandert vom `root` in die Wallpaper-View. Der bestehende
   Inset-Listener bleibt am `root` (systemBars|ime-Padding); die Wallpaper-View
   füllt den ganzen Screen inkl. hinter den System-Leisten.

3. **`AsciiWallpaperView` (Custom `View`):**
   - Felder: aktuelles Motiv (`List<String>`), `Paint` (Monospace, `fgColor` mit
     reduziertem Alpha = gedimmt, Startwert ~`0x40`, am Gerät tunen).
   - `onSizeChanged`: "Contain"-Skalierung berechnen + cachen
     (`scale = min(breite/max.Spalten, höhe/Zeilenzahl)`) → Motiv passt komplett
     rein (kein Crop), zentriert, Letterbox-Schwarz oben/unten.
   - `onDraw`: ein `drawText` pro Zeile, statisch (nur bei Motivwechsel/Resize neu).
   - `setMotif(lines)`: setzen + `invalidate()`.

4. **Motiv-Content (`object AsciiArt`):** Liste `Motif(name, lines)`, jedes Motiv als
   eingebettete Kotlin-String-Konstante. Start mit 2–3 sparse, bildschirmfüllenden
   Stücken (Bergkette, Konstellation, Welle …) + "off". **Der eigentliche kreative
   Aufwand steckt hier**, nicht im Code.

5. **Persistenz:** `ChopperConfig` bekommt `wallpaper: String = ""` (Motiv-Name,
   `""` = aus); `ConfigJson` serialisiert/parst es wie `names` (rückwärtskompatibel,
   fehlender Key → `""`). Beim Laden Motiv auflösen und auf die View setzen.

6. **Auswahl-Command (kein neues Sigil):** Enter-Handler analog zu `~`: `~art` →
   nächstes Motiv (inkl. "off" durchrotieren), auf die View setzen, `cfg.wallpaper`
   speichern. Reine, testbare Logik: `LauncherLogic.nextWallpaper(current, names)`.

7. **Lesbarkeit:** garantiert durch sparse Motive + Dim (gilt auch für die
   Edit-Modi).

8. **Docs & Tests:** README `~art` + 0.3-Notiz + Prompt-Grammar-Legende. JVM-Tests
   für `nextWallpaper` (Rotation + Off-Wrap) und `ConfigJson`-Round-Trip für
   `wallpaper`; Skalierungs-Mathematik ggf. als reine Funktion rausziehen und testen.

9. **Verifikation:** Build + Install, dann **Dim-Alpha und Contain-Scale am Gerät
   tunen** — der einzige Punkt, der Auge braucht.

### Offene Design-Punkte
- Wie viele Motive zum Start und welche.
- Ob `~art` nur durchrotiert oder auch `~art <name>` direkt anspringt.
