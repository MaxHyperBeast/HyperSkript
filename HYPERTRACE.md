# HyperTrace

HyperTrace is HyperSkript's built-in script profiler. It shows which script lines, loops, triggers and functions are
slowing your server down, down to the exact line.

It only measures while you run a capture (or turn on the rolling buffer). The rest of the time it does nothing and
costs nothing (see [Cost](#cost)).

## Quick start

1. Run `/hypertrace start 60s` (or `/hypertrace start` and stop it yourself).
2. Play normally, or do the thing that lags.
3. Run `/hypertrace stop` (with a time, it stops by itself).

The slowest lines appear in chat; hover a line for details. The full report is saved as an HTML file in
`plugins/Skript/hypertrace/`. Open it in any browser; it works offline.

For lag you can't predict, turn on the [rolling buffer](#rolling-buffer-and-lag-spike-clips): it measures all the time
and saves a report by itself when a lag spike happens.

Example chat output:

```
HyperTrace » capture stopped, 60 s, 1,200 ticks
 • Skript used 2.31 ms per tick on average, 23% of the average tick (9.80 ms; 50 ms is the limit before the server lags).
 • The most expensive line combat.sk:42 (loop all players): 34% of all Skript time, 0.79 ms per tick; it runs very often (120× per tick).
 Slowest lines (own time per tick, hover for details):
 1. 0.79 ms    34% combat.sk:42  loop all players
 2. 0.41 ms    18% stats.sk:17  set {stats::%uuid of player%::kills} to ...
```

## Commands

Permission: `skript.hypertrace` (operators by default). Alias: `/htrace`.

| Command | What it does |
|---|---|
| `/hypertrace start [time]` | Starts a capture. With a time (`30s`, `5m`, `1h`), it stops by itself and saves the report. The player who started it sees its time and cost in their action bar. |
| `/hypertrace stop` | Stops the capture, shows the summary and saves the report. |
| `/hypertrace status` | Summary of the running (or last) capture. |
| `/hypertrace lines [n]` | The slowest lines, by their own time. |
| `/hypertrace blocks [n]` | The slowest loops and sections, including all lines inside them. |
| `/hypertrace triggers [n]` | The slowest events, commands and periodic triggers, including their event filters. |
| `/hypertrace functions [n]` | The slowest functions. |
| `/hypertrace events [n]` | The Bukkit events that cost Skript the most. |
| `/hypertrace variables [n]` | The global variables written most often, and which line writes them. |
| `/hypertrace loops` | Loops running right now, their current iteration and how fast they climb (useful for loops with waits). |
| `/hypertrace report` | Saves the report without stopping. |
| `/hypertrace rolling on\|off` | Turns the always-on rolling buffer on or off (kept after restarts). |
| `/hypertrace clip` | Saves a report of the rolling buffer: the last one to two minutes. |
| `/hypertrace reset` | Clears everything and starts over. |

## The report

Each report is one HTML file (plus the same data as `.json`). It has a filter box that narrows every table to a
script, trigger or piece of code, and a light/dark switch.

| Section | What it shows |
|---|---|
| At a glance | Capture length and TPS, average tick, Skript's share of the tick, ticks over 50 ms, the slowest tick and what caused it, and how many loaded triggers ran. |
| Findings | Plain sentences about what stands out most: the most expensive lines and why (runs very often, slow every time, causes spikes, loops a lot, spends its time in a function it calls), the biggest loop, event filters that are checked far more often than they run, the slowest tick and what ran in it (or that it was a garbage collection pause), loops that are still running, saved variables that are written very often, freezes, and triggers that never ran. |
| Time by script | Which scripts use the most time. Click one to open it. |
| Tick spread | How many ticks fell into each time band, for Skript's time or the whole tick, with the typical, worst 5% and worst 1% tick. |
| Slowest ticks card | The 10 slowest ticks one by one: how much of the 50 ms budget they used, Skript's part, the triggers and the slowest line in each. |
| Tick activity | A chart of every tick (whole tick and Skript's part, the 50 ms limit, garbage collection pauses, saves of the variables file, the slowest ticks). Hover for one tick and what ran in it; drag across it to zoom into a moment. Below it, **what ran in the selected range**: every trigger and function with its time, runs and an activity graph for exactly that range. |
| Slowest lines | Every line that ran: own time and total time per tick, share of all Skript time, runs per tick, average and slowest run (and whether a garbage collection pause was part of it), worst single tick, and why it is expensive. A line that is normally fast but had one very slow run is marked as such, so a pause of the whole server isn't blamed on it. |
| Slowest blocks | Loops, `if`s and other sections with the time of everything inside them. |
| Triggers / Functions | Every trigger and function with an activity graph: runs (and parts that ran after a `wait`), total time, event filter time and how often the filter let it through, own lines, average and slowest run, worst tick. |
| Events | The Bukkit events Skript handled, how often they fired and what all their triggers cost. |
| Loops | Iterations, the most iterations in one run, the most copies running at once, and whether each loop is still running (with its current iteration and iterations per second). |
| Global variables | Writes over time; created, updated and deleted counts and the net growth per variable or list (`{stats::*}`); whether it is saved to disk; how many different names; the last value's type; the line that writes it most; and every save of the variables file with how long it took and the file size. |
| Slowest ticks | The 10 slowest ticks (and the 10 where Skript used the most time) with their garbage collection pause, triggers and lines. Click a tick to see it in the chart. |
| Never ran | Loaded triggers, commands and functions that didn't run during the capture, and how often their event fired but the filter said no. |
| Freezes | If the server stopped ticking during the capture: the script line that was running, the sections around it and its trigger. |
| Scripts | Every script that ran, with its time. |

**Clicking any location** (`combat.sk:42`) opens the script viewer: the script's code with syntax colors, every line
colored by its cost with its own time, runs per tick and block time next to it (hover for all numbers), and the
hottest lines of that script or trigger at the top. Function calls in the code are links: hover one to preview the
function, click it to open it.

### Own time and total time

- **Own time** is the time spent in the line itself: evaluating its expressions and running its effect.
- **Total time** also includes what the line starts: functions it calls, events it fires (for example `teleport`
  firing a teleport event with its own triggers) and section bodies.

A line with a large total but a small own time is slow because of what it calls; follow it into that function or
trigger. **Block time** of a loop or section is the total of every line inside it, so it shows the real cost of a
whole loop.

## Rolling buffer and lag spike clips

Lag spikes rarely happen while a capture is running. With `/hypertrace rolling on`, HyperTrace measures all the time
in windows of `window-seconds` (default 60) and always keeps the last one or two windows.

- `/hypertrace clip` saves a report of them (between one and two minutes).
- When a tick takes longer than `spike-ms` (default 250 ms), a **lag spike clip** is saved by itself a few seconds later,
  so it shows what led up to the spike and what happened right after. Operators get a chat message with the slowest
  line of that tick and the report's file name. At most one automatic clip is saved per `spike-cooldown-seconds`.

The rolling buffer costs the same as a running capture, all the time (see [Cost](#cost)), so it is off by default.

## Settings

`plugins/Skript/hypertrace.yml` is created on first start. Changes apply the next time you use `/hypertrace`;
`rolling.enabled` is read at startup (`/hypertrace rolling on|off` switches it right away and saves it).

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns HyperTrace off completely. |
| `line-timing` | `true` | Time every line. `false` only times events, triggers, functions and ticks, which costs a little less while capturing. |
| `max-capture-minutes` | `30` | A capture without a time stops by itself after this long. `0` = never. |
| `hang-detection-seconds` | `10` | If the server stops ticking this long during a capture, the running script line is written to the console. `0` = off. |
| `chat-lines` | `8` | How many lines the chat summaries show. |
| `indicator` | `true` | Show the player who started a capture its time and cost in the action bar. |
| `report.include-sources` | `true` | Put the scripts' source into the report for the script viewer. Turn off before sharing reports with people who shouldn't see your scripts. |
| `report.write-json` | `true` | Also save the report's data as `.json`. |
| `rolling.enabled` | `false` | The always-on rolling buffer. |
| `rolling.window-seconds` | `60` | Length of one window; a clip covers one to two windows. |
| `rolling.spike-ms` | `250` | A tick at least this slow saves a lag spike clip. `0` = only `/hypertrace clip`. |
| `rolling.spike-cooldown-seconds` | `300` | At most one automatic clip per this many seconds. |
| `rolling.spike-after-seconds` | `5` | How long after a spike the clip is saved. |

## Cost

- **Nothing running:** nothing is measured. Skript checks one flag when a trigger starts, when an event is handled
  and when a global variable is set. In benchmarks this is not measurable.
- **During a capture or with the rolling buffer on:** every line run costs about 25 ns more on a typical machine. That
  is small next to most lines, but noticeable in very tight loops (hundreds of thousands of lines per tick). The report
  shows how much time the measuring itself took. That time is not counted in the lines' own times.
- Captures stop by themselves (`max-capture-minutes`), so a forgotten capture can't keep running.

## How it works

Skript runs every trigger by stepping from one line to the next in a single place (`TriggerItem.walk`). While
measuring, HyperTrace times each step there. Each line's number is recorded when the script is loaded, so lines are
matched exactly; nothing is guessed from the line's text. HyperTrace never changes or rewires your scripts, so it can't
change how they run, and it's safe to use on a live server.

Limits:

- Lag that isn't Skript (other plugins, chunk loading, world saves) only shows as tick time Skript didn't use. Garbage
  collection pauses are shown separately: they stop the whole server, so whatever script line was running at that
  moment looks slow. For everything else, use a general profiler such as spark
  (`/spark profiler start --only-ticks-over 50`).
- Only the server thread is measured. Scripts running on other threads (async events) are counted but not timed; the
  report says how many there were.
- Code that addons run outside of Skript's triggers isn't measured. Addon effects and conditions used inside your
  scripts are measured like any other line.

## JSON data

Next to each `report.html` is a `.json` file with the same data: every line, trigger, function, event, loop and
variable group with its numbers, per-tick times, per-trigger activity per tick, the slowest ticks, variable file
saves, triggers that never ran, freezes, the findings and (if enabled) the scripts' source. Use it to compare
captures, feed other tools, or ask an AI to analyze a capture.

## Compared to SkTrace

SkTrace is a separate profiler plugin and works on HyperSkript too (see [ADDONS.md](ADDONS.md)).

| | HyperTrace | SkTrace |
|---|---|---|
| Install | Built in | Separate plugin |
| Per-line timing | Always available, exact line numbers, nothing is rewired | Experimental option that rewrites the trigger structure |
| Own vs total time per line, loop and section totals | Yes | No |
| Interactive tick chart with range breakdown | Yes | Yes |
| Time by script, tick spread with percentiles, slowest tick | Yes (all 10 slowest ticks, with their lines) | Yes (the slowest tick) |
| Script viewer with per-line cost and function previews | Yes | Yes |
| Event filter cost and pass rate, events | Yes | Events |
| Variables: writes, created/updated/deleted, last type, file saves | Yes, plus the line that writes most | Yes |
| Loops: peak, running, iterations per second | Yes | Yes |
| Triggers that never ran | Listed | Counted |
| Freeze detection | Running line, the sections around it and its trigger | Running loop |
| Rolling buffer and clips | Yes, plus automatic lag spike clips | Yes |
| Plain-language findings | Yes | No |
| Report | Local HTML + JSON | HTML (uploaded for a share link) + JSON |
