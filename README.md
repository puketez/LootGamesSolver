# LGSolver

**Solver overlay and puzzle tracer for LootGames.** Minecraft **1.7.10** · **Forge** · **client-side only**

LGSolver reads the LootGames puzzle state your client already has and draws it
back to you: which Minesweeper cells are guaranteed safe, which are guaranteed
mines, the odds on everything else, and which Simon Says cell comes next. It also
puts a tracer on puzzle blocks so you can find one without sweeping F3 for the
block animation.

It is **hint-only**. It never clicks, flags, or sends a packet for you.

---

## Features

### Minesweeper solver

Reads the board and paints every cell it can reason about.

| Overlay             | Meaning                                                                             |
| ------------------- | ----------------------------------------------------------------------------------- |
| 🟩 Green             | Guaranteed safe — left-click it                                                     |
| 🟥 Red               | Guaranteed mine — right-click to flag                                               |
| 🟨 Yellow → 🟧 orange | Not certain either way; shade and opacity scale with risk, labelled with the mine % |

Three stages of reasoning: constraint propagation, subset reasoning (the 1-2-1
pattern and friends), then exact probability enumeration over the frontier with a
global mine-budget term for the cells no clue touches. When the board has a forced
move, LGSolver finds it. When it genuinely doesn't, you get real numbers instead of
a guess.

Solving runs on a background thread, so even a full 19×19 stage-4 board costs zero
frame time.

### Simon Says (Game of Light)

Captures the flashed sequence while it plays, then highlights **only the next cell**
to click with a `step / total` counter. No more losing your place twenty symbols in.

Toggling the overlay off mid-round keeps the sequence — it is only broadcast once,
during the flash.

### Puzzle tracer

Draws a through-wall box, a tracer line, and a distance label on every LootGames
puzzle master in range.

| Colour    | Target                  |
| --------- | ----------------------- |
| 🟡 Gold    | Un-started puzzle block |
| 🔵 Cyan    | Active Minesweeper      |
| 🟣 Magenta | Active Simon Says       |

---

## Controls

| Action                                         | Default      |
| ---------------------------------------------- | ------------ |
| Toggle solver hints (Minesweeper + Simon Says) | <kbd>V</kbd> |
| Toggle puzzle tracer                           | <kbd>B</kbd> |

Rebindable under **Options → Controls → LGSolver**. Both toggles persist across
restarts.

### Commands

| Command            | Effect                                                             |
| ------------------ | ------------------------------------------------------------------ |
| `/lgsolver dump`   | Print the bound board, Simon Says sequence, and tracer diagnostics |
| `/lgsolver rebind` | Re-resolve the LootGames classes after a version change            |

### Config

`config/lgsolver.cfg`

| Option                  | Default | Effect                                 |
| ----------------------- | ------- | -------------------------------------- |
| `renderHints`           | `true`  | Minesweeper and Simon Says overlays    |
| `renderEsp`             | `true`  | Puzzle tracer                          |
| `showProbabilityLabels` | `true`  | Mine-% text on guess cells             |
| `debugLogging`          | `false` | Verbose reflection and capture logging |

---

## Requirements

- Minecraft **1.7.10** with **Forge**
- **LootGames** — a soft dependency. Without it, LGSolver logs a notice and stays dormant.

Built and tested against the GTNH build of LootGames. Client-side only: it does
nothing on a dedicated server and does not need to be installed on one.

---

## Performance

The mod is built to be invisible when you are not looking at a puzzle.

- **~0.07 ms/s** of client-thread work while playing, and **zero** when both toggles are off.
- Solving happens off the render thread, so no board size can drop a frame.
- No `TileEntity` or `World` references are held across ticks — nothing for the mod to leak.

---

## Why hint-only

1. It is a solver, not an autoplayer. On a true 50/50 or a first click, the call is yours.
2. No packets are sent and no input is suppressed, so there is nothing for a server to see.
3. If LootGames changes its internals, only the reflective reader degrades — the mod
   logs a warning and disables itself instead of crashing.

---

## Building

```bash
./gradlew build
```

Output lands in `build/libs/`. Use `lgsolver-<version>.jar`; the `-dev.jar` is
workspace-mapped and not for distribution.

Uses [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle), the
GTNH fork of ForgeGradle for 1.7.10. The wrapper is pinned to Gradle 8.9, the daemon
runs on JDK 21, and output targets Java 8 bytecode.

Run the tests with `./gradlew test`.

---

## License

**GNU Lesser General Public License v3.0** — see [LICENSE](LICENSE), which
incorporates the [GPL-3.0](COPYING) by reference.

Copyright © 2026 Puket.

**Modpack authors: no permission needed.** Include it, redistribute it, ship it
in whatever pack you like. Modified versions of LGSolver's own source must stay
under the LGPL.
