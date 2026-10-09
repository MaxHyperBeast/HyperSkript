# Addon compatibility

Addons verified to work with HyperSkript. An addon is listed as verified when it loads without errors and the
[addon test script](addon-tests/addontest.sk) gives exactly the same results on HyperSkript as on the official
Skript release of the same version.

**Last verified:** 2026-10-09 · HyperSkript 2.16.2 (compared with official Skript 2.16.2) · Purpur 26.3 build 2645 · Java 25

| Addon | Version | Status | What was checked |
|---|---|---|---|
| [SkBee](https://github.com/ShaneBeee/SkBee) | 3.26.0 | ✅ Verified | NBT compounds and tags, items with custom NBT, text components |
| [skript-reflect](https://github.com/SkriptLang/skript-reflect) | 2.6.3 | ✅ Verified | Imports, static and instance calls, creating Java objects |
| [skript-worldguard](https://github.com/SkriptLang/skript-worldguard) | 1.0.1 | ✅ Verified | Creating regions, regions at a location, region priority (WorldGuard 7.0.19, WorldEdit 7.4.6-beta-02) |
| [oopsk](https://github.com/sovdeeth/oopsk) | 1.0-beta2 | ✅ Verified ¹ | Struct templates, struct instances, field access, reloading a script with a template |
| [SkCheese](https://github.com/erenkarakal/SkCheese) | 1.8 | ✅ Verified | Bitwise operations, hex literals, switch sections |
| [skript-gui](https://github.com/APickledWalrus/skript-gui) | 1.4.0 | ✅ Verified | Creating a GUI with items in its slots |
| [DiSky](https://github.com/DiSkyOrg/DiSky) | 4.29.0 | ✅ Verified ² | Loading, embed builders |
| [skript-placeholders](https://github.com/APickledWalrus/skript-placeholders) | 1.7.1 | ✅ Verified | Registering a custom placeholder and reading it from Skript and from `/papi parse` (PlaceholderAPI 2.12.3) |
| SkTrace (by KALPE) | 0.1.5 | ✅ Verified ³ | Trigger, function and loop profiling, in normal and line-level mode; the report has the same structure as on official Skript |

¹ oopsk registers types at runtime by changing Skript's registries through reflection. HyperSkript's lookup caches
are cleared whenever that happens, and the converter/comparator caches are still reachable as the maps oopsk expects
(fixed during this check: oopsk failed to load struct templates before).

² Tested without a connected bot (no bot token on the test server), so only syntax that works offline was run.

³ SkTrace reads loop counters and rewires triggers through reflection. HyperSkript keeps a live view of its loop
counters in the field SkTrace reads, and does not cache jumps through `if`/`else` chains, which SkTrace rewires in
line-level mode (fixed during this check). SkTrace's per-event trigger hooks are unavailable on Skript 2.16.2 itself
("No Trigger objects found inside SkriptEventHandler"); HyperSkript behaves the same.

## How addons are checked

1. The addon and its dependencies are installed next to HyperSkript on a clean test server.
2. The server must start without errors and `/sk info` must list the addon.
3. [addontest.sk](addon-tests/addontest.sk) runs one or more checks per addon and logs the results, then reloads
   itself and runs again (this re-registers runtime types, e.g. oopsk's templates).
4. SkTrace profiles the script's periodic triggers (a loop, a while loop, a function call, an `if`/`else` chain and a
   loop that waits across ticks), once normally and once with line-level profiling.
5. Everything is repeated with the official Skript jar of the same version. Results, script errors, placeholder
   output and the SkTrace report structure must be identical.

Skript's own test suite (799 script tests, 123 JUnit tests) passes as well.

## Adding an addon

Add checks for it to `addon-tests/addontest.sk`, run the comparison above and add a row to the table. If an addon
works on official Skript but not on HyperSkript, that is a HyperSkript bug: please report it.
