# HyperTrace

HyperTrace is HyperSkript's built-in script profiler. It shows which script lines, loops, triggers and functions are
slowing your server down, down to the exact line.

It only measures while you run a capture. The rest of the time it does nothing and costs nothing (see
[Cost](#cost)).

## Quick start

1. Run `/hypertrace start 60s` (or `/hypertrace start` and stop it yourself).
2. Play normally, or do the thing that lags.
3. Run `/hypertrace stop` (with a time, it stops by itself).

The slowest lines appear in chat; hover a line for details. The full report is saved as an HTML file in
`plugins/Skript/hypertrace/`. Open it in any browser.

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
| `/hypertrace start [time]` | Starts a capture. With a time (`30s`, `5m`, `1h`), it stops by itself and saves the report. |
| `/hypertrace stop` | Stops the capture, shows the summary and saves the report. |
| `/hypertrace status` | Summary of the running (or last) capture. |
| `/hypertrace lines [n]` | The slowest lines, by their own time. |
| `/hypertrace blocks [n]` | The slowest loops and sections, including all lines inside them. |
| `/hypertrace triggers` | The slowest events, commands and periodic triggers, including their event filters. |
| `/hypertrace functions` | The slowest functions. |
| `/hypertrace loops` | Loops running right now and their current iteration (useful for loops with waits). |
| `/hypertrace report` | Saves the report without stopping. |
| `/hypertrace reset` | Clears everything and starts over. |

## The report

| Section | What it shows |
|---|---|
| Summary | Capture length, average tick, Skript's share of the tick, slowest tick, ticks over 50 ms. |
| Findings | Plain sentences about what stands out most: the most expensive lines and why (runs very often, slow every time, causes spikes, loops a lot, spends its time in a function it calls), the biggest loop, event filters that are checked far more often than they run, the slowest tick and what ran in it, saved variables that are written very often, and freezes. |
| Tick times | A chart of every tick: total tick time and Skript's part, with the 50 ms limit. |
| Slowest lines | Every line that ran: own time and total time per tick, share of all Skript time, runs per tick, average and slowest run, worst single tick, and why it is expensive. |
| Slowest blocks | Loops, `if`s and other sections with the time of everything inside them. |
| Triggers / Functions / Events | Every trigger and function: runs, total time, event filter time and how often the filter let it run, runs after a `wait`, slowest run, worst tick. |
| Slowest ticks | The 10 slowest ticks (and the 10 where Skript used the most time) with the triggers and lines that ran in them. |
| Loops | Iterations and the most iterations in a single run. |
| Global variable writes | Writes per variable or list (`{stats::*}`), whether they are saved to disk, how many different names, and which line writes them most. |
| Freezes | If the server stopped ticking during the capture: the script line that was running, the sections around it and its trigger. |
| Scripts | Each script's source with every line colored by its cost and its numbers next to it. Locations everywhere in the report link here. |

Tables can be sorted by clicking a column.

### Own time and total time

- **Own time** is the time spent in the line itself: evaluating its expressions and running its effect.
- **Total time** also includes what the line starts: functions it calls, events it fires (for example `teleport`
  firing a teleport event with its own triggers) and section bodies.

A line with a large total but a small own time is slow because of what it calls; follow it into that function or
trigger. **Block time** of a loop or section is the total of every line inside it, so it shows the real cost of a
whole loop.

## Settings

`plugins/Skript/hypertrace.yml` is created on first start. Changes apply the next time you use `/hypertrace`.

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns HyperTrace off completely (`/hypertrace` won't start captures). |
| `line-timing` | `true` | Time every line. `false` only times events, triggers, functions and ticks, which costs a little less while capturing. |
| `max-capture-minutes` | `30` | A capture without a time stops by itself after this long. `0` = never. |
| `hang-detection-seconds` | `10` | If the server stops ticking this long during a capture, the running script line is written to the console. `0` = off. |
| `chat-lines` | `8` | How many lines the chat summaries show. |

## Cost

- **No capture running:** nothing is measured. Skript checks one flag when a trigger starts, when an event is
  handled and when a global variable is set. In benchmarks this is not measurable.
- **During a capture:** every line run costs about 25 ns more on a typical machine. That is small next to most
  lines, but noticeable in very tight loops (hundreds of thousands of lines per tick). The report shows how much time
  the measuring itself took. That time is not counted in the lines' own times.
- Captures stop by themselves (`max-capture-minutes`), so a forgotten capture can't keep running.

## How it works

Skript runs every trigger by stepping from one line to the next in a single place
(`TriggerItem.walk`). During a capture, HyperTrace times each step there. Each line's number is recorded when the
script is loaded, so lines are matched exactly; nothing is guessed from the line's text. HyperTrace never changes or
rewires your scripts, so it can't change how they run, and it's safe to use on a live server.

Limits:

- Only the server thread is measured. Scripts running on other threads (async events) are counted but not timed; the
  report says how many there were.
- Code that addons run outside of Skript's triggers isn't measured. Addon effects and conditions used inside your
  scripts are measured like any other line.

## Compared to SkTrace

SkTrace is a separate profiler plugin and works on HyperSkript too (see
[ADDONS.md](ADDONS.md)).

| | HyperTrace | SkTrace |
|---|---|---|
| Install | Built in | Separate plugin |
| Per-line timing | Always available, exact line numbers, nothing is rewired | Experimental option that rewrites the trigger structure |
| Own vs total time per line | Yes | No |
| Loop and section totals | Yes | No |
| Event filter cost and pass rate | Yes | No |
| Slowest ticks with their lines | Yes | Worst tick |
| Freeze detection | Running line, the sections around it and its trigger | Running loop |
| Global variable writes | Grouped by list, with the line that writes most | Yes |
| Report | Local HTML file | HTML, uploaded for a share link |
| Always-on rolling buffer | No | Yes |
