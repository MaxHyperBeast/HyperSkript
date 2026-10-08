# HyperSkript

A performance build of Skript 2.16.2. Scripts, addons and saved variables work unchanged: the plugin is still
called `Skript`, reports version `2.16.2` (flavor `selfbuilt-hyperskript`), and every change keeps the exact
behaviour of the original code. Skript's own regression suite (799 test scripts) passes.

## Results

Measured on Purpur 26.3 (Java 25) against the official Skript 2.16.2 jar. Best of 3 alternating server runs per build;
both builds produced identical results in every case.

| | Skript 2.16.2 | HyperSkript | |
|---|---|---|---|
| One tick of typical script work for 50 simulated players | 1.17 ms | 0.19 ms | **6.0×** |
| `send "<red>Hello <bold>%x%</bold> world"` | 10.72 µs | 0.31 µs | **34.8×** |
| `formatted` / `colored` text with expressions | 6.19 / 10.35 µs | 0.13 / 0.12 µs | **49× / 85×** |
| `is tagged with` | 0.88 µs | 0.29 µs | **3.0×** |
| Looping a list variable | | | **2.4×** |
| Event dispatch, with ~300 other triggers loaded | 1.77 µs | 0.92 µs | **1.9×** |
| Geometric mean of 60 micro-benchmarks | | | **1.81×** |
| Loading 40 scripts (12,960 lines): startup / `/sk reload` | 3.35 s / ~2.3 s | 2.44 s / ~1.3 s | **1.37× / 1.73×** |

Correctness: Skript's test suite passes (799/799 script tests, 123/123 JUnit tests, same as the unmodified source);
870 templated text components were compared with a full parse with no differences; saved variable files are
byte-identical to the official build's.

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

## Debugging aid

Start the server with `-Dhyperskript.verifyTemplates=true` to compare every templated text component with a full
parse; differences are logged as `[HyperSkript] Template mismatch`.

## Building

`./gradlew build` with Java 25 produces `build/libs/Skript-2.16.2.jar`.
