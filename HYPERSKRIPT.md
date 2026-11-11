# HyperSkript

A performance build of Skript 2.17.1. Scripts, addons and saved variables work unchanged: the plugin is still
called `Skript`, reports version `2.17.1` (flavor `selfbuilt-hyperskript`), and every optimization keeps the exact
behaviour of the original code (the only differences are the [bug fixes](#bug-fixes) listed below). Skript's own
regression suite (856 test scripts) passes.

## Results

Measured on Purpur 26.3 (Java 25) against the official Skript 2.17.1 jar. Best of 3 alternating server runs per build;
both builds produced identical results in every case.

| | Skript 2.17.1 | HyperSkript | |
|---|---|---|---|
| One tick of typical script work for 50 simulated players | 1.08 ms | 0.19 ms | **5.6×** |
| `send "<red>Hello <bold>%x%</bold> world"` | 10.04 µs | 0.31 µs | **32×** |
| `formatted` / `colored` text with expressions | 6.42 / 9.83 µs | 0.13 / 0.12 µs | **48× / 80×** |
| `all armor stands` with 2,000 of them loaded | 154 µs | 6.0 µs | **26×** |
| `{_entity} is an armor stand` | 0.78 µs | 0.18 µs | **4.4×** |
| `is tagged with` | 0.82 µs | 0.28 µs | **3.0×** |
| Looping a list variable | | | **2.9×** |
| Event dispatch, with ~300 other triggers loaded | 1.86 µs | 0.95 µs | **1.9×** |
| Geometric mean of 60 micro-benchmarks | | | **1.77×** |
| Loading 40 scripts (12,960 lines): startup / `/sk reload` | 3.51 s / ~2.5 s | 2.53 s / ~1.5 s | **1.39× / 1.7×** |

The micro-benchmark times are per operation, after subtracting the cost of an empty loop of the same build. Two more
cases (`all pigs` and `nearest pig` among 2,000 other entities) are left out of the mean: their time depends on
what earlier cases left in the test world, and a run in a cleared world measured both builds the same (6.6 and 6.4 µs).
The numbers for Skript 2.16.2 (HyperSkript 1.0.0) are in the
[1.0.0 release notes](https://github.com/MaxHyperBeast/HyperSkript/releases/tag/1.0.0).

Correctness: Skript's test suite passes (856/856 script tests and 122/122 JUnit tests on Paper 26.3, and the script
tests on Paper 1.21.11 and 1.21.5 with Java 21, same as the unmodified source); 870 templated text components were
compared with a full parse with no differences; a variables file with 134 saved values of 24 types is byte-identical to
the one the official build saves; every entity type name gives the same result for every entity type that can be
spawned.

## What was changed

### Runtime

| Area | Before | After |
|---|---|---|
| Text formatting (`send "<red>Hi %player%"`, `formatted "..."`, `colored "..."`, any string used as a component) | Every evaluation built the full string, ran 3 regexes and a full MiniMessage parse | `ComponentTemplate`: the static parts are parsed once with placeholders; values that can't contain formatting are put into the parsed component. Falls back to the original full parse whenever equivalence isn't guaranteed (values with `< > & § \`, empty values, expressions inside tags or after `&`/`§`, gradients/rainbows/unknown tags, link parsing). |
| Text parser | No caching | Plain text (no `<`, `\`, `&`, `§`) skips MiniMessage (verified identical on 390k random strings); results cached per input string (bounded, invalidated on config change, disabled if an addon registers a tag resolver); regexes only run when their trigger characters are present |
| Event dispatch | Every fired event scanned every registered trigger on the server (with a synchronized lookup per event class) and built a new list, once per priority | Trigger arrays cached per event class and priority, invalidated whenever triggers are registered or unregistered; no task/lambda allocation when already on the main thread |
| Loops (`loop`, `while`) | 4-6 `WeakHashMap` operations per iteration | One state object per running loop plus a one-entry fast path |
| Type lookups (`Classes.getSuperClassInfo`, comparators, converters, arithmetic) | Global locks and a key allocation per lookup, or unsynchronized maps | Lock-free, allocation-free caches |
| Converting values to text (`%x%`) | Linear scan over every registered type until one matches | Cached per class |
| Entity type checks (`victim is a zombie`, `player`, `the zombie`, entity filters, `all zombies`) | Compared the entity against every registered entity type (~200 interface checks since Skript 2.17) to find its closest type | Closest type cached per entity class, cleared when a type is registered (4× faster checks, identical for all 163 entity type names on every entity type that can be spawned) |
| Tag checks (`is tagged with`) | Built the tag's complete value set on every check, only to read its element type | CraftBukkit tags check the type through their own cast (verified in their bytecode) |
| `contains`, property expressions (`length of`, `name of`, ...) | Streams and arrays rebuilt per evaluation | Plain loops, cached target types |
| List variables | Name evaluated twice, string built per element, 4-5 intermediate arrays | Single pass |
| Local variable writes | Split array and tree walk for every name | Direct path for names without `::` |
| `getSingle()` | Copied the value array | Reads it directly |
| Functions | Call registered with its signature on every call | Once per signature |
| Variables while a save runs | Every global variable read scanned the queue of pending writes | O(1) lookup of the latest pending write |
| Variable file saving | Two method calls per hex digit | Lookup table |
| `wait` | Built a debug message after every delay | Only when debugging |

### Script loading

| Area | Before | After |
|---|---|---|
| Pattern matching | Every pattern of every syntax: log clearing, a parsing stack entry and a possibly locking map lookup, before the cheap keyword check rejected it | Cheap check first, lock-free compiled pattern lookup |
| Keyword checks | Nested streams | Plain loops; the lowercase input is computed once per line instead of once per pattern |
| Type names / literal parsing | Regex or scan over every registered type per lookup | Cached |

### Syntax audit

After the general changes above, every one of the 1066 syntax elements (expressions, conditions, effects, sections,
events, structures, literals and default functions) was reviewed for its own runtime cost, including the ones added
or rewritten in Skript 2.17. The full list, with what was found and changed for each element, is in
[SYNTAX-AUDIT.md](SYNTAX-AUDIT.md). Highlights:

| Syntax | Before | After |
|---|---|---|
| `... matches "regex"` | Compiled every regex for every string, on a parallel stream (shared thread pool) per check | Patterns compiled once and reused; plain loops |
| `replace all regex ...`, `split ... at {_x}` | Compiled the regex on every call | Last compiled pattern reused |
| `on break of stone` and other block events with a type filter | New item type per trigger per event | Reused while the block data is equal |
| `on player enter chunk` | Two chunk lookups on every player move | Chunk coordinate comparison |
| `data tag "x" of ...` | Parsed and validated the key on every use | Last valid key remembered |
| `location(x, y, z)` | Copied the world list on every call | Only when no world is given |
| `formatNumber(n, "...")`, `... formatted as {_format}` | Parsed the format pattern on every call | Last format reused per thread |
| `set chat format` | Compiled its placeholder regex for every message | Compiled once |
| `level of <enchantment> of ...`, `filter {_list::*}`, `contains`, `is wearing` | Streams per evaluation | Plain loops |
| `amount of <item> in <inventory>` | New item type per slot per item checked | Once per slot |
| `on walk on <block>` | Two block lookups on every player move | None while the player stays in the same block column at the same height (it never fires then) |

Behaviour was kept exactly; a few possible changes were left out on purpose because they would change results
(for example evaluating random expressions once instead of per player).

## Bug fixes

| Problem in Skript 2.10 – 2.17.1 | Fix |
|---|---|
| `/sk reload all` loaded the aliases on a background thread and then unloaded and loaded every script on that thread, while the server kept running (players could use half-loaded scripts). A second `/sk reload all` sent before the first finished ran at the same time: one of them could fail without a message, and the other left orphaned copies of the scripts' triggers that ran on every event (twice, three times, ...) until a restart. `/sk reload aliases` had the same overlap. | Both load the aliases on the server thread again, as Skript did before 2.10 and as startup does: the server waits for the reload like it does for `/sk reload scripts`, and a second reload starts only after the first is done. `on load` triggers now run during `/sk reload all`, like in every other reload, instead of one tick later. |

## HyperTrace (built-in profiler)

`/hypertrace start 60s` measures every script line, loop, trigger, function and event on the server thread and shows
the slowest lines in chat, with an interactive HTML report (zoomable tick chart, script viewer, variables, loops,
triggers that never ran) and its JSON data in `plugins/Skript/hypertrace/`. An optional rolling buffer measures all
the time and saves a report by itself after a lag spike. Lines are matched by the line
numbers recorded at load time and nothing in the scripts is rewired, so it is exact and safe on a live server. While no
capture runs it costs nothing measurable. It can be turned off in `plugins/Skript/hypertrace.yml`. See
[HYPERTRACE.md](HYPERTRACE.md).

## Addon compatibility

Common addons (SkBee, skript-reflect, skript-worldguard, oopsk, SkCheese, skript-gui, DiSky, skript-placeholders,
SkTrace) are verified to give the same results as on official Skript; see [ADDONS.md](ADDONS.md) for versions and how
they are checked. Some addons change Skript's internals through reflection, so HyperSkript keeps those internals
reachable in their old form: the converter and comparator caches are still maps keyed by class pairs, loop counters are
still readable from `LoopSection.currentLoopCounter`, and all registry caches are cleared whenever types,
converters, comparators or operations are registered, including at runtime.

## Debugging aid

Start the server with `-Dhyperskript.verifyTemplates=true` to compare every templated text component with a full
parse; differences are logged as `[HyperSkript] Template mismatch`.

## Building

`./gradlew build` with Java 25 produces `build/libs/Skript-2.17.1.jar`.
