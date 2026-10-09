# Syntax audit

Every expression, condition, effect, section, event, structure, literal and default function in HyperSkript, reviewed
one section at a time for runtime performance. **Status:** ✅ OK = reviewed, nothing worth changing;
⚡ Optimized = changed (the note says what was slow and what changed); ⏳ = not reviewed yet.
Every change keeps the exact behaviour; Skript's test suite must pass after each section.

**Progress:** 722 of 1054 reviewed, 41 optimized.

| Section | Contents | Reviewed |
|---|---|---|
| [1](#section-1) | Most used syntax | 91/91 |
| [2](#section-2) | Events | 151/151 |
| [3](#section-3) | Default functions | 80/80 |
| [4](#section-4) | conditions | 80/80 |
| [5](#section-5) | conditions, effects | 80/80 |
| [6](#section-6) | effects, expressions | 80/80 |
| [7](#section-7) | expressions | 80/80 |
| [8](#section-8) | expressions | 80/80 |
| [9](#section-9) | expressions | 0/80 |
| [10](#section-10) | expressions, hooks.chat.expressions, hooks.economy.expressions, ... | 0/80 |
| [11](#section-11) | bukkit.enchantments.elements.expressions, bukkit.entity.displays.elements.expressions, bukkit.entity.displays.item.elements.expressions, ... | 0/80 |
| [12](#section-12) | bukkit.itemcomponents.equippable.elements.expressions, bukkit.loottables.elements.conditions, bukkit.loottables.elements.effects, ... | 0/80 |
| [13](#section-13) | common.properties.elements.expressions | 0/12 |

## Section 1

Most used syntax

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `%inventories% (can hold\|ha(s\|ve) [enough] space (for\|to hold)) %...` | Condition | CondCanHold | ✅ OK | Inventory space checks in Bukkit dominate. |
| `chance of %number%(1:\\%\|) [fail:(fails\|failed)]` | Condition | CondChance | ✅ OK | One random number. |
| `rawtypes` | Condition | CondCompare | ⚡ Optimized | Comparator lookups were locked and allocated a key per comparison; now lock-free cached (general type-lookup change). |
| `%inventories% (has\|have) %itemtypes% [in [(the[ir]\|his\|her\|its)...` | Condition | CondContains | ⚡ Optimized | When the container type is only known at runtime, it ran up to 3 streams over the values per check; now one loop with the same checks. |
| `%date% (was\|were)( more\|(n't\| not) less) than %timespan% [ago]` | Condition | CondDate | ✅ OK | Simple date arithmetic. |
| `(alive\|1¦dead)` | Condition | CondIsAlive | ✅ OK | One Bukkit call. |
| `empty` | Condition | CondIsEmpty | ✅ OK | Simple type checks. |
| `of type[s] %itemtypes/entitydatas%` | Condition | CondIsOfType | ✅ OK | Item comparison work is required by the semantics. |
| `(online\|:offline\|:connected)` | Condition | CondIsOnline | ✅ OK | One Bukkit call. |
| `%~objects% (exist[s]\|(is\|are) set)` | Condition | CondIsSet | ✅ OK | Evaluates the expression once; reads benefit from the faster variable access. |
| `wearing %itemtypes%` | Condition | CondIsWearing | ⚡ Optimized | Built a stream over all equipment slots for every entity checked; now a plain loop over a cached slot array. |
| `[%livingentities%] ha(s\|ve) %itemtypes% in [main] hand` | Condition | CondItemInHand | ✅ OK | Item comparison required by the semantics. |
| `%commandsenders% (has\|have) [the] permission[s] %strings%` | Condition | CondPermission | ✅ OK | Permission checks in Bukkit dominate. |
| `cancel [the] event` | Effect | EffCancelEvent | ✅ OK | A few instanceof checks. |
| ` can't have anything added to it` | Effect | EffChange | ⚡ Optimized | Value cloning looked up the class info through an unsynchronized map with a slow fallback; now a lock-free cache (general change). |
| `[execute] [the] [bungee:bungee[cord]] command[s] %strings% [by %-co...` | Effect | EffCommand | ✅ OK | Command dispatch in Bukkit dominates. |
| `continue [this loop\|[the] [current] loop]` | Effect | EffContinue | ✅ OK | Benefits from the new loop state objects (exit is cheaper). |
| `drop %itemtypes/experiences% [%directions% %locations%] [(1¦without...` | Effect | EffDrop | ✅ OK | Entity spawning dominates. |
| `(exit\|stop) [trigger]` | Effect | EffExit | ✅ OK | Benefits from the new loop state objects. |
| `damage` | Effect | EffHealth | ✅ OK | Damage/heal calls dominate. |
| `kill %entities%` | Effect | EffKill | ✅ OK | Damage call dominates. |
| `log %strings% [(to\|in) [file[s]] %-strings%] [with [the\|a] severi...` | Effect | EffLog | ✅ OK | File writes dominate; writers are already cached. |
| `play sound[s] %strings% ` | Effect | EffPlaySound | ✅ OK | Packet sending dominates. |
| `return %objects%` | Effect | EffReturn | ✅ OK | Benefits from the function call changes. |
| `\\(%object%\\)[ ]` | Expression | ExprArithmetic | ⚡ Optimized | Operation lookups took two synchronized-map locks and allocated a key per calculation; now lock-free (general change). |
| `[the] (amount\|number\|size) of %numbered%` | Expression | ExprAmount | ✅ OK | List size reads the map size directly. |
| `[the] last arg[ument]` | Expression | ExprArgument | ✅ OK | Simple array access. |
| `[the] (attacker\|damager)` | Expression | ExprAttacker | ✅ OK | Simple event access. |
| `[the] [event-]block` | Expression | ExprBlock | ✅ OK | Event value (see EventValueExpression). |
| `[the] (full\|complete\|whole) command` | Expression | ExprCommand | ✅ OK | Simple string work. |
| `(0¦x\|1¦y\|2¦z)(-\| )(coord[inate]\|pos[ition]\|loc[ation])[s]` | Expression | ExprCoordinate | ✅ OK | One getter. |
| `%timespan% (ago\|in the past\|before [the] [date] %-date%)` | Expression | ExprDateAgoLater | ✅ OK | Simple date arithmetic. |
| `%objects% (otherwise\|?) %objects%` | Expression | ExprDefaultValue | ✅ OK | Evaluates the fallback only when needed. |
| `[the] distance between %location% and %location%` | Expression | ExprDistance | ✅ OK | One Bukkit call. |
| `unchecked` | Expression | ExprElement | ✅ OK | Iterator based; cost depends on the source expression. |
| `[(all [[of] the]\|the)] %*entitydatas% [(in\|of) (world[s] %-worlds...` | Expression | ExprEntities | ✅ OK | Entity scans in the server dominate (measured unchanged). |
| `[the] event-<.+>` | Expression | ExprEventExpression | ✅ OK | Event value (see EventValueExpression). |
| `%objects% (where\|that match) \\[<.+>\\]` | Expression | ExprFilter | ✅ OK | Cost is the filter condition per element. |
| `health` | Expression | ExprHealth | ✅ OK | One getter per entity. |
| `[(the\|all [[of] the])] (indexes\|indices) of %~objects%` | Expression | ExprIndices | ⚡ Optimized | List key reads are single pass now (general list variable change); the expression itself is fine. |
| `inventor(y\|ies)` | Expression | ExprInventory | ✅ OK | Proxy creation only for item containers (rare). |
| `item[[ ]stack] (amount\|size\|number)` | Expression | ExprItemAmount | ✅ OK | One getter. |
| `[all [[of] the]] items ([with]in\|of\|contained in\|out of) [1:inve...` | Expression | ExprItemsIn | ✅ OK | Lazy iterator over slots. |
| `(concat[enate]\|join) %strings% [(with\|using\|by) [[the] delimiter...` | Expression | ExprJoinSplit | ⚡ Optimized | 'split ... at {_x}' compiled a new regex on every call when the delimiter isn't a literal; the last compiled delimiter is now reused. |
| `length` | Expression | ExprLength | ⚡ Optimized | Property expressions built a Stream per evaluation; now a plain loop (general change). |
| `[the] [event-](location\|position)` | Expression | ExprLocation | ✅ OK | Event value (see EventValueExpression). |
| `[the] loop(-\| )(counter\|iteration)[-%-*number%]` | Expression | ExprLoopIteration | ⚡ Optimized | The loop counter was a WeakHashMap read per use; now a field of the loop state. |
| `^(.+)-(\\d+)$` | Expression | ExprLoopValue | ⚡ Optimized | Current/next/previous values were WeakHashMap reads; now fields of the loop state. |
| `metadata [(value\|tag)[s]] %strings% of %metadataholders%` | Expression | ExprMetadata | ✅ OK | Bukkit metadata lookups dominate. |
| `name[s]` | Expression | ExprName | ✅ OK | One getter (measured 2.1x faster from the general changes). |
| `now` | Expression | ExprNow | ✅ OK | One allocation. |
| `[(all [[of] the]\|the)] (numbers\|1¦integers\|2¦decimals) (between\...` | Expression | ExprNumbers | ✅ OK | Lazy iterator when looped. |
| `%string% parsed as (%-*classinfo%\|\"<.*>\")` | Expression | ExprParse | ⚡ Optimized | Type parsing scanned all registered types per call; candidate parsers are now cached per type (general change). |
| `ping` | Expression | ExprPing | ✅ OK | One getter. |
| `[a] random %*classinfo% [out] of %objects%` | Expression | ExprRandom | ✅ OK | One random pick. |
| `[a\|%-integer%] random (:integer\|number)[s] (from\|between) %numbe...` | Expression | ExprRandomNumber | ✅ OK | Uses ThreadLocalRandom. |
| `floored` | Expression | ExprRound | ✅ OK | Simple math. |
| `[all [[of] the]\|the\|every] %*classinfo%` | Expression | ExprSets | ✅ OK | Cost is the supplied values. |
| `sorted %objects%` | Expression | ExprSortedList | ✅ OK | Sorting is the required work. |
| `%strings% in (0¦upper\|1¦lower)[ ]case` | Expression | ExprStringCase | ✅ OK | String conversion is the required work. |
| `[the] (part\|sub[ ](text\|string)) of %strings% (between\|from) (in...` | Expression | ExprSubstring | ✅ OK | Simple string work. |
| `[the] [actual:(actual[ly]\|exact)] target[ed] block[s] [of %livinge...` | Expression | ExprTargetedBlock | ✅ OK | Ray trace in the server dominates. |
| `%objects% if <.+>[,] (otherwise\|else) %objects%` | Expression | ExprTernary | ✅ OK | Evaluates one branch only. |
| `[the] time since %dates%` | Expression | ExprTimeSince | ✅ OK | Simple date arithmetic. |
| `[the] (tool\|held item\|weapon) [of %livingentities%]` | Expression | ExprTool | ✅ OK | Slot wrapper allocation only. |
| `uuid[s]` | Expression | ExprUUID | ✅ OK | One getter. |
| `[the] world [of %locations/entities/chunk%]` | Expression | ExprWorld | ✅ OK | One getter. |
| `(spawn\|summon) ` | Effect | EffSecSpawn | ✅ OK | Entity spawning dominates. |
| `any` | Section | SecConditional | ⚡ Optimized | Every taken branch walked past all remaining else ifs/elses to find the code after the chain; the result is now cached after the first run (the chain is fixed once loaded). |
| `(for [each]\|loop) [value] %~object% in %objects%` | Section | SecFor | ⚡ Optimized | Uses the loop state objects of SecLoop (no WeakHashMap operations per iteration). |
| `loop %objects%` | Section | SecLoop | ⚡ Optimized | 4-6 WeakHashMap operations per iteration; now one state object per running loop plus a fast path (2.4x faster list loops). |
| `[:do] while <.+>` | Section | SecWhile | ⚡ Optimized | Loop counter and do-while flag were WeakHashMap operations per iteration; now a state object (2.5x faster). |
| `usage` | Structure | StructCommand | ✅ OK | Load time only. |
| `[on] [:uncancelled\|:cancelled\|any:(any\|all)] <.+> [priority:with...` | Structure | StructEvent | ⚡ Optimized | Event dispatch scanned every trigger on the server per event; now cached per event class and priority (1.9x faster dispatch). |
| `[:local] function <.+>` | Structure | StructFunction | ⚡ Optimized | Function calls re-registered themselves with their signature on every call; now once. |
| `options` | Structure | StructOptions | ✅ OK | Load time only. |
| `variables` | Structure | StructVariables | ⚡ Optimized | Every variable access in a script with this structure pushed a new HashMap on a synchronized deque; now only when default variables exist. |
| `[:force] teleport %entities% (to\|%direction%) %location% [[while] ...` | Effect | EffTeleport | ✅ OK | Teleport in the server dominates. |
| `game[ ]mode` | Expression | ExprGameMode | ✅ OK | One getter. |
| `[the] lore of %itemtype%` | Expression | ExprLore | ✅ OK | Item meta copy in Bukkit dominates. |
| `tagged (as\|with) %minecrafttags%` | Condition | CondIsTagged | ⚡ Optimized | Built the tag's complete value set on every check only to read its element type; CraftBukkit tags now check the type through their own cast (3-4.5x faster). |
| `send [the] action[ ]bar [with text] %object% [to %audiences%]` | Effect | EffActionBar | ⚡ Optimized | Text with expressions is formatted from a pre-parsed template (general text change). |
| `broadcast %objects% [(to\|in) %-worlds%]` | Effect | EffBroadcast | ⚡ Optimized | Text with expressions is formatted from a pre-parsed template (general text change). |
| `(message\|send [message[s]]) %objects% [to %audiences%]` | Effect | EffMessage | ⚡ Optimized | Every send with expressions ran 3 regexes and a full MiniMessage parse; now a pre-parsed template (35x faster). |
| `[to %audiences%] [for %-timespan%] [with fade[(-\| )]in %-timespan%...` | Effect | EffSendTitle | ⚡ Optimized | Text with expressions is formatted from a pre-parsed template (general text change). |
| `[negated:(un\|non)[-]](colo[u]r-\|colo[u]red )%strings%` | Expression | ExprColored | ⚡ Optimized | formatted/colored with expressions now use a pre-parsed template; static text is cached (49-220x faster). |
| `raw %strings%` | Expression | ExprRawString | ✅ OK | No parsing involved. |
| `%strings% where [(first:[the] first instance[s]\|all instances) of]...` | Expression | ExprReplace | ⚡ Optimized | Regex replacement compiled every pattern on every call; the patterns of the last needles are now reused. |
| `%objects% contain[1:s] %objects%` | Condition | PropCondContains | ⚡ Optimized | Rebuilt its target type array with a stream per check; now cached until the property map grows (2.8x faster). |
| `wait %timespan%` | Effect | Delay | ⚡ Optimized | Built a debug message string after every wait even with debug off; now only when debugging. |
| `event-<value> (all event values)` | Expression | EventValueExpression | ✅ OK | Resolution is cached by the event value registry. A per-expression cache was considered but skipped: context-dependent values must stay uncached, and the measured cost is small. |

## Section 2

Events

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `Attempt Attack` | Event | EvtAttemptAttack | ✅ OK | A few instanceof checks. |
| `*At Time` | Event | EvtAtTime | ✅ OK | Scheduled internally, no per-event check. |
| `Beacon Effect` | Event | EvtBeaconEffect | ✅ OK | Simple field checks. |
| `Beacon Toggle` | Event | EvtBeaconToggle | ✅ OK | Simple instanceof checks. |
| `Break / Mine` | Event | EvtBlock | ⚡ Optimized | Every trigger with a type filter ("on break of stone") built a new item type from the block on every event. The item type only depends on the block data, so the last one is reused while the block data is equal (full block state comparison); the block data is also read once instead of twice. |
| `Book Edit` | Event | EvtBookEdit | ✅ OK | One getter. |
| `Book Sign` | Event | EvtBookSign | ✅ OK | One getter. |
| `Click` | Event | EvtClick | ✅ OK | Interaction tracking and type checks are required by the semantics. |
| `Command` | Event | EvtCommand | ⚡ Optimized | Matched the command against the listed commands with a stream on every command; now a loop. |
| `Damage` | Event | EvtDamage | ✅ OK | Entity type checks. |
| `Death` | Event | EvtEntity | ✅ OK | Entity type checks. |
| `Enderman/Sheep/Silverfish/Falling Block` | Event | EvtEntityBlockChange | ✅ OK | Entity type checks. |
| `Entity Shoot Bow` | Event | EvtEntityShootBow | ✅ OK | Entity type checks. |
| `Target` | Event | EvtEntityTarget | ✅ OK | One getter. |
| `Entity Transform` | Event | EvtEntityTransform | ✅ OK | Simple checks. |
| `Experience Change` | Event | EvtExperienceChange | ✅ OK | One getter. |
| `Experience Spawn` | Event | EvtExperienceSpawn | ✅ OK | Handled internally, no per-event check. |
| `Firework Explode` | Event | EvtFirework | ✅ OK | Builds a color set per check, but firework explosions are rare. |
| `First Join` | Event | EvtFirstJoin | ✅ OK | One getter. |
| `Grow` | Event | EvtGrow | ✅ OK | Type checks required by the semantics. |
| `Harvest Block` | Event | EvtHarvestBlock | ✅ OK | Type checks. |
| `Heal` | Event | EvtHealing | ✅ OK | Simple loops. |
| `Dispense` | Event | EvtItem | ✅ OK | Item type checks required by the semantics. |
| `Leash / Unleash` | Event | EvtLeash | ✅ OK | Entity type checks. |
| `Level Change` | Event | EvtLevel | ✅ OK | Two getters. |
| `Move / Rotate` | Event | EvtMove | ✅ OK | Four comparisons per move; no allocations. |
| `Move On` | Event | EvtMoveOn | ✅ OK | Uses its own listener with block lookups per move; required by the semantics. |
| `*Periodical` | Event | EvtPeriodical | ✅ OK | Scheduled task, no per-event check. |
| `Block Growth` | Event | EvtPlantGrowth | ✅ OK | Builds an item type per listed type, but growth events with filters are rare. |
| `Armor Change` | Event | EvtPlayerArmorChange | ✅ OK | One getter. |
| `Player Chunk Enter` | Event | EvtPlayerChunkEnter | ⚡ Optimized | Looked up the chunk of both locations on every player move to compare them; now compares the world and chunk coordinates directly (identical to the chunk equality check, verified in the server code). |
| `Send Command List` | Event | EvtPlayerCommandSend | ✅ OK | Copies the command list, required for the event values. |
| `Portal` | Event | EvtPortal | ✅ OK | One instanceof check. |
| `Pressure Plate / Trip` | Event | EvtPressurePlate | ✅ OK | Tag lookup on a constant tag. |
| `System Time` | Event | EvtRealTime | ✅ OK | Scheduled task, no per-event check. |
| `Resource Pack Request Response` | Event | EvtResourcePackResponse | ✅ OK | One getter. |
| `Script Load/Unload` | Event | EvtScript | ✅ OK | Called by Skript itself, no per-event check. |
| `Server Start/Stop` | Event | EvtSkript | ✅ OK | Called by Skript itself, no per-event check. |
| `Spectate` | Event | EvtSpectate | ✅ OK | Entity type checks. |
| `Teleport` | Event | EvtTeleport | ✅ OK | Entity type checks. |
| `Vehicle Collision` | Event | EvtVehicleCollision | ✅ OK | Type checks, rare event. |
| `Weather Change` | Event | EvtWeatherChange | ✅ OK | Simple checks. |
| `World Save` | Event | EvtWorld | ✅ OK | One comparison. |
| `Region Enter/Leave` | Event | EvtRegionBorder | ✅ OK | Region lookups per move happen in the region plugin. |
| `Smelt` | Event | EvtFurnace | ✅ OK | One item type per event, required by the comparison. |
| `Entity Breed` | Event | EvtBreed | ✅ OK | Entity type checks. |
| `Brewing Complete` | Event | EvtBrewingComplete | ✅ OK | Simple loops. |
| `Brewing Fuel` | Event | EvtBrewingFuel | ✅ OK | Simple loop. |
| `Brewing Start` | Event | EvtBrewingStart | ✅ OK | One instanceof check. |
| `Player GameMode Change` | Event | EvtPlayerGameModeChange | ✅ OK | One comparison. |
| `Player Pick Item` | Event | EvtPlayerPickItem | ✅ OK | Type checks. |
| `Bucket Catch Entity` | Event | EvtBucketEntity | ✅ OK | Stream per event, but bucket events are rare. |
| `Fishing` | Event | EvtFish | ✅ OK | One comparison. |
| `org.bukkit.event.player.PlayerInputEvent` | Event | EvtPlayerInput | ⚡ Optimized | Built a new set of the listed keys on every input event; the keys are a literal, so the set is built once. |
| `Entity Potion Effect` | Event | EvtEntityPotion | ✅ OK | Streams per event over a few literal values; potion events are infrequent. |
| `[block] can build check` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `block damag(ing\|e)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[block] flow[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[block] ignit(e\|ion)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[block] physics` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `piston extend[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `piston retract[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `redstone [current] [chang(e\|ing)]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `spread[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `chunk load[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `chunk (generat\|populat)(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `chunk unload[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `creeper power` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `zombie break[ing] [a] [wood[en]] door` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `combust[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `explo(d(e\|ing)\|sion)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `portal enter[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[entity] tam(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `explosion prime` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `(food\|hunger) (level\|met(er\|re)\|bar) chang(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `leaves decay[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `lightning [strike]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `pig[ ]zap` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bed enter[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bed leav(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bucket empty[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bucket fill[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `throw[ing] [of] [an] egg` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] tool break[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `item damag(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player['s]] (tool\|item held\|held item) chang(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] (login\|logging in\|join[ing])` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] connect[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] (kick\|being kicked)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `(quit[ting]\|disconnect[ing]\|log[ ]out\|logging out\|leav(e\|ing))` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] respawn[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] toggl(e\|ing) sneak` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] toggl(e\|ing) sprint` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `portal creat(e\|ion)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `projectile hit` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `projectile collide` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[projectile] (shoot\|launch)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `sign (chang[e]\|edit)[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[world] spawn change` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vehicle create` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vehicle damage` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vehicle destroy` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vehicle enter` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vehicle exit` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `mount[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `dismount[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `(gliding state change\|toggl(e\|ing) gliding)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `(area\|AoE) [cloud] effect` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `sheep [re]grow[ing] wool` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `inventory open[ed]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `inventory clos(ing\|e[d])` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `slime split[ting]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[entity] resurrect[ion] [attempt]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] world chang(ing\|e[d])` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] flight toggl(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] (language\|locale) chang(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] jump[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `swap[ping of] [(hand\|held)] item[s]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `server [list] ping` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[entity] toggl(e\|ing) swim` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[use of] riptide [enchant[ment]]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `sponge absorb` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[item] enchant prepare` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[item] enchant` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `inventory pick[ ]up` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `horse jump` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[block] fertilize` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] arm swing` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `item mend[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `anvil prepar(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `player trad(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `entity jump[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `anvil damag(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] (stop\|end) (using item\|item use)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] ((ready\|choose\|draw\|load) arrow\|arrow (choose\|draw\|l...` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] inventory slot chang(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] deep sleep[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `[player] (pick[ing\| ]up [an] arrow\|arrow pick[ing\| ]up)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `inventory drag[ging]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `piglin (barter[ing]\|trad(e\|ing))` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bell ring[ing]` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bell resonat(e\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `enderman (enrage\|anger)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `beacon change effect` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `broadcast` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `player (experience\|[e]xp) cooldown change` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vehicle move` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `elytra boost` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `bat toggle sleep` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `vault display[ing] item` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |
| `villager career chang(e[d]\|ing)` | Event | SimpleEvents | ✅ OK | Shared SimpleEvent: the check always passes, so the only cost is dispatch (cached per event class). |

## Section 3

Default functions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `(ai\|artificial intelligence)` | Condition | CondAI | ✅ OK | One Bukkit getter. |
| `(duplicate\|clone)` | Condition | CondAllayCanDuplicate | ✅ OK | One Bukkit getter. |
| `%strings% (is\|are) alphanumeric` | Condition | CondAlphanumeric | ✅ OK | Simple string check. |
| `respawn anchors [do[1:(n't\| not)]] work in %worlds%` | Condition | CondAnchorWorks | ✅ OK | One Bukkit getter. |
| `[the] event is cancel[l]ed` | Condition | CondCancelled | ✅ OK | One getter. |
| `fly` | Condition | CondCanFly | ✅ OK | One Bukkit getter. |
| `pick([ ]up items\| items up)` | Condition | CondCanPickUpItems | ✅ OK | One Bukkit getter. |
| `%entities% (is\|are) [visible\|:invisible] for %players%` | Condition | CondCanSee | ✅ OK | One Bukkit getter. |
| `see chat colo[u]r[s\|ing]` | Condition | CondChatColors | ✅ OK | One Bukkit getter. |
| `(chat\|text) filtering (on\|enabled)` | Condition | CondChatFiltering | ✅ OK | One Bukkit getter. |
| `%player% can see all messages [in chat]` | Condition | CondChatVisibility | ✅ OK | One Bukkit getter. |
| `[the] damage (was\|is\|has)(0¦\|1¦n('\|o)t) [been] (caused\|done\|m...` | Condition | CondDamageCause | ✅ OK | One comparison. |
| `[the] (boosting\|used) firework will be consumed` | Condition | CondElytraBoostConsume | ✅ OK | One Bukkit getter. |
| `been stared at` | Condition | CondEndermanStaredAt | ✅ OK | One Bukkit getter. |
| `in (` | Condition | CondEntityIsInLiquid | ✅ OK | One Bukkit getter. |
| `wet` | Condition | CondEntityIsWet | ✅ OK | One Bukkit getter. |
| `[the] entity storage of %blocks% (is\|are) full` | Condition | CondEntityStorageIsFull | ✅ OK | Block state snapshot is required to read the storage. |
| `despawn (on chunk unload\|when far away)` | Condition | CondEntityUnload | ✅ OK | One Bukkit getter. |
| `%entities% (is\|are) from a [mob] spawner` | Condition | CondFromMobSpawner | ✅ OK | One Bukkit getter. |
| `glowing text` | Condition | CondGlowingText | ✅ OK | Block state snapshot is required to read the sign. |
| `((any\|a) horn\|left:[a] left horn[s]\|right:[a] right horn[s]\|bot...` | Condition | CondGoatHasHorns | ✅ OK | One Bukkit getter. |
| `[a] (client\|custom) weather [set]` | Condition | CondHasClientWeather | ✅ OK | One Bukkit getter. |
| `[custom] model data [1:floats\|2:flags\|3:strings\|4:colo[u]rs]` | Condition | CondHasCustomModelData | ✅ OK | Item meta read is required. |
| `%players% (has\|have) [([an] item\|a)] cooldown (on\|for) %itemtypes%` | Condition | CondHasItemCooldown | ✅ OK | Bukkit cooldown lookups. |
| `%livingentities% (has\|have) [a] [direct] line of sight to %entitie...` | Condition | CondHasLineOfSight | ✅ OK | Ray trace in the server dominates. |
| `%metadataholders% (has\|have) metadata [(value\|tag)[s]] %strings%` | Condition | CondHasMetadata | ✅ OK | Bukkit metadata lookup. |
| `[a] resource pack [(loaded\|installed)]` | Condition | CondHasResourcePack | ✅ OK | One Bukkit getter. |
| `[the] score[ ]board tag[s] %strings%` | Condition | CondHasScoreboardTag | ✅ OK | Reads the live tag set (no copy, verified in the server code). |
| `[creeper[s]] %livingentities% ((is\|are)\|1¦(isn't\|is not\|aren't\...` | Condition | CondIgnitionProcess | ✅ OK | One Bukkit getter. |
| `%entities% ((is\|are) incendiary\|cause[s] a[n] (incendiary\|fiery)...` | Condition | CondIncendiary | ✅ OK | One Bukkit getter. |
| `%offlineplayers/strings% (is\|are) banned` | Condition | CondIsBanned | ✅ OK | Ban list lookups in the server dominate. |
| `([a] block\|blocks)` | Condition | CondIsBlock | ✅ OK | One Bukkit getter. |
| `(blocking\|defending) [with [a] shield]` | Condition | CondIsBlocking | ✅ OK | One Bukkit getter. |
| `%blocks% (is\|are) redstone powered` | Condition | CondIsBlockRedstonePowered | ✅ OK | Redstone power check in the server. |
| `(burning\|ignited\|on fire)` | Condition | CondIsBurning | ✅ OK | One Bukkit getter. |
| `(charged\|powered)` | Condition | CondIsCharged | ✅ OK | One Bukkit getter. |
| `abs(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `acos(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `asin(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `atan(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `axisAngle(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `calcExperience(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `ceil(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `ceiling(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `clamp(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `combinations(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `concat(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `cos(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `date(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `entity(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `exp(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `factorial(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `floor(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `formatNumber(...)` | Function | Function | ⚡ Optimized | With a custom format, parsed the format pattern into a new DecimalFormat on every call. The last format is now kept per thread (DecimalFormat isn't thread-safe) and reused while the pattern is the same. |
| `fromBase(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `isNaN(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `ln(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `location(...)` | Function | Function | ⚡ Optimized | Called Bukkit.getWorlds() (copies the world list) on every call to get the default world, even when a world was given. Now only when the world argument is left out; a given but unset world still gives a location without a world, as before (caught by the test suite during the change). |
| `log(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `max(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `mean(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `median(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `min(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `mod(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `offlineplayer(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `permutations(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `player(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `product(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `quaternion(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `rgb(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `root(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `round(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `sin(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `sqrt(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `sum(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `tan(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `toBase(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `uuid(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `vector(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |
| `world(...)` | Function | Function | ✅ OK | Simple math/lookup body; the call overhead is shared by all functions and was reduced earlier (function reference fast path). |

## Section 4

conditions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `charging [a] fireball` | Condition | CondIsChargingFireball | ✅ OK | One or two Bukkit getters / type checks. |
| `climbing` | Condition | CondIsClimbing | ✅ OK | One or two Bukkit getters / type checks. |
| `[:un]conditional` | Condition | CondIsCommandBlockConditional | ✅ OK | One or two Bukkit getters / type checks. |
| `%entities%'[s] custom name[s] (is\|are) visible` | Condition | CondIsCustomNameVisible | ✅ OK | One or two Bukkit getters / type checks. |
| `dancing` | Condition | CondIsDancing | ✅ OK | One or two Bukkit getters / type checks. |
| `dashing` | Condition | CondIsDashing | ✅ OK | One or two Bukkit getters / type checks. |
| `%numbers% (is\|are) evenly divisible by %number% [with [a] toleranc...` | Condition | CondIsDivisibleBy | ✅ OK | Simple math. |
| `eating` | Condition | CondIsEating | ✅ OK | One or two Bukkit getters / type checks. |
| `edible` | Condition | CondIsEdible | ✅ OK | One or two Bukkit getters / type checks. |
| `(fire resistant\|resistant to fire)` | Condition | CondIsFireResistant | ✅ OK | One or two Bukkit getters / type checks. |
| `flammable` | Condition | CondIsFlammable | ✅ OK | One or two Bukkit getters / type checks. |
| `flying` | Condition | CondIsFlying | ✅ OK | One or two Bukkit getters / type checks. |
| `frozen` | Condition | CondIsFrozen | ✅ OK | One or two Bukkit getters / type checks. |
| `[furnace] fuel` | Condition | CondIsFuel | ✅ OK | One or two Bukkit getters / type checks. |
| `gliding` | Condition | CondIsGliding | ✅ OK | One or two Bukkit getters / type checks. |
| `%livingentities%'[s] [:main] hand[s] (is\|are) raised` | Condition | CondIsHandRaised | ✅ OK | One or two Bukkit getters / type checks. |
| `infinite` | Condition | CondIsInfinite | ✅ OK | One or two Bukkit getters / type checks. |
| `interactable` | Condition | CondIsInteractable | ✅ OK | One or two Bukkit getters / type checks. |
| `(invisible\|:visible)` | Condition | CondIsInvisible | ✅ OK | One or two Bukkit getters / type checks. |
| `(invulnerable\|invincible)` | Condition | CondIsInvulnerable | ✅ OK | One or two Bukkit getters / type checks. |
| `jumping` | Condition | CondIsJumping | ✅ OK | One or two Bukkit getters / type checks. |
| `(:left\|right)( \|-)handed` | Condition | CondIsLeftHanded | ✅ OK | One or two Bukkit getters / type checks. |
| `chunk[s] %directions% [%locations%] (is\|are)[(1¦(n't\| not))] loaded` | Condition | CondIsLoaded | ✅ OK | Chunk/world lookups in the server. |
| `occluding` | Condition | CondIsOccluding | ✅ OK | One or two Bukkit getters / type checks. |
| `on [the] ground` | Condition | CondIsOnGround | ✅ OK | One or two Bukkit getters / type checks. |
| `[[a] server\|an] op[erator][s]` | Condition | CondIsOp | ✅ OK | One or two Bukkit getters / type checks. |
| `passable` | Condition | CondIsPassable | ✅ OK | One or two Bukkit getters / type checks. |
| `pathfinding [to[wards] %-livingentity/location%]` | Condition | CondIsPathfinding | ✅ OK | Pathfinder reads in the server. |
| `persistent` | Condition | CondIsPersistent | ✅ OK | One or two Bukkit getters / type checks. |
| `playing dead` | Condition | CondIsPlayingDead | ✅ OK | One or two Bukkit getters / type checks. |
| `plugin[s] %strings% (is\|are) enabled` | Condition | CondIsPluginEnabled | ✅ OK | Plugin lookup by name. |
| `%itemtypes% (is\|are) %` | Condition | CondIsPreferredTool | ✅ OK | Tool check in the server. |
| `resonating` | Condition | CondIsResonating | ✅ OK | One or two Bukkit getters / type checks. |
| `riding [%-entitydatas/entities%]` | Condition | CondIsRiding | ✅ OK | Entity type checks. |
| `ringing` | Condition | CondIsRinging | ✅ OK | One or two Bukkit getters / type checks. |
| `riptiding` | Condition | CondIsRiptiding | ✅ OK | One or two Bukkit getters / type checks. |
| `[:properly] saddled` | Condition | CondIsSaddled | ⚡ Optimized | 'is properly saddled' created a new saddle item for every check to compare with; now one constant. |
| `screaming` | Condition | CondIsScreaming | ✅ OK | One or two Bukkit getters / type checks. |
| `sedated` | Condition | CondIsSedated | ✅ OK | One or two Bukkit getters / type checks. |
| `(sheared\|shorn)` | Condition | CondIsSheared | ✅ OK | One or two Bukkit getters / type checks. |
| `silent` | Condition | CondIsSilent | ✅ OK | One or two Bukkit getters / type checks. |
| `[a] s(k\|c)ript (command\|cmd)` | Condition | CondIsSkriptCommand | ✅ OK | One or two Bukkit getters / type checks. |
| `sleeping` | Condition | CondIsSleeping | ✅ OK | One or two Bukkit getters / type checks. |
| `([a] slime chunk\|slime chunks\|slimey)` | Condition | CondIsSlimeChunk | ✅ OK | One or two Bukkit getters / type checks. |
| `sneaking` | Condition | CondIsSneaking | ✅ OK | One or two Bukkit getters / type checks. |
| `solid` | Condition | CondIsSolid | ✅ OK | One or two Bukkit getters / type checks. |
| `%entitydatas% is spawnable [in [the [world]] %world%]` | Condition | CondIsSpawnable | ✅ OK | Server check. |
| `sprinting` | Condition | CondIsSprinting | ✅ OK | One or two Bukkit getters / type checks. |
| `stackable` | Condition | CondIsStackable | ✅ OK | One or two Bukkit getters / type checks. |
| `swimming` | Condition | CondIsSwimming | ✅ OK | One or two Bukkit getters / type checks. |
| `tameable` | Condition | CondIsTameable | ✅ OK | One or two Bukkit getters / type checks. |
| `(tamed\|domesticated)` | Condition | CondIsTamed | ✅ OK | One or two Bukkit getters / type checks. |
| `ticking` | Condition | CondIsTicking | ✅ OK | One or two Bukkit getters / type checks. |
| `transparent` | Condition | CondIsTransparent | ✅ OK | One or two Bukkit getters / type checks. |
| `[:un]breakable` | Condition | CondIsUnbreakable | ✅ OK | One or two Bukkit getters / type checks. |
| `%script% is using %strings%` | Condition | CondIsUsingFeature | ✅ OK | Script data lookups. |
| `valid` | Condition | CondIsValid | ✅ OK | One or two Bukkit getters / type checks. |
| `normali(s\|z)ed` | Condition | CondIsVectorNormalized | ✅ OK | One or two Bukkit getters / type checks. |
| `[the] server (is\|not:(isn't\|is not)) (in white[ ]list mode\|white...` | Condition | CondIsWhitelisted | ✅ OK | One or two Bukkit getters / type checks. |
| `%locations% (is\|are) within %location% and %location%` | Condition | CondIsWithin | ✅ OK | Bounding box checks required by the semantics. |
| `(despawn naturally\|naturally despawn)` | Condition | CondItemDespawn | ✅ OK | One or two Bukkit getters / type checks. |
| `leashed` | Condition | CondLeashed | ✅ OK | One or two Bukkit getters / type checks. |
| `[the] (lead\|leash) [item] (will\|not:(won't\|will not)) (drop\|be ...` | Condition | CondLeashWillDrop | ✅ OK | One or two Bukkit getters / type checks. |
| `[the] lid[s] of %blocks% (is\|are) (open[ed]\|:close[d])` | Condition | CondLidState | ✅ OK | One or two Bukkit getters / type checks. |
| `%strings% (1¦match[es]\|2¦do[es](n't\| not) match) %strings%` | Condition | CondMatches | ⚡ Optimized | For every string it compiled every regex again, on a parallel stream (shared thread pool) per check, and full matching compiled each regex a second time. Patterns are now cached for the last regex list and compiled once, in order, when first needed; the checks are plain loops with the same and/or logic. |
| `running [(1¦below)] minecraft %string%` | Condition | CondMinecraftVersion | ✅ OK | One or two Bukkit getters / type checks. |
| `on (its\|their) back[s]` | Condition | CondPandaIsOnBack | ✅ OK | One or two Bukkit getters / type checks. |
| `rolling` | Condition | CondPandaIsRolling | ✅ OK | One or two Bukkit getters / type checks. |
| `scared` | Condition | CondPandaIsScared | ✅ OK | One or two Bukkit getters / type checks. |
| `sneezing` | Condition | CondPandaIsSneezing | ✅ OK | One or two Bukkit getters / type checks. |
| `%dates% (is\|are)[negated:(n't\| not)] in the (past\|:future)` | Condition | CondPastFuture | ✅ OK | One date per value. |
| `%offlineplayers% [(has\|have\|did)] [already] play[ed] [on (this\|t...` | Condition | CondPlayedBefore | ✅ OK | One or two Bukkit getters / type checks. |
| `(is PvP\|PvP is) enabled [in %worlds%]` | Condition | CondPvP | ✅ OK | One or two Bukkit getters / type checks. |
| `[the] resource pack (was\|is\|has) [been] %resourcepackstate%` | Condition | CondResourcePack | ✅ OK | One getter. |
| `[the] respawn location (was\|is)[1:(n'\| no)t] [a] (:bed\|respawn a...` | Condition | CondRespawnLocation | ✅ OK | One getter. |
| `script[s] [%-strings%] (is\|are) loaded` | Condition | CondScriptLoaded | ✅ OK | Script lookups, rarely used in hot code. |
| `%strings% (start\|1¦end)[s] with %strings%` | Condition | CondStartsEndsWith | ✅ OK | Plain string checks. |
| `shivering` | Condition | CondStriderIsShivering | ✅ OK | One or two Bukkit getters / type checks. |
| `[the] [entire\|:additional] tool[ ]tip[s] of %itemtypes% (is\|are) ...` | Condition | CondTooltip | ✅ OK | One or two Bukkit getters / type checks. |
| `[the] egg (:will\|will not\|won't) hatch` | Condition | CondWillHatch | ✅ OK | One or two Bukkit getters / type checks. |

## Section 5

conditions, effects

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `within %number% (block\|metre\|meter)[s] (around\|of) %locations%` | Condition | CondWithinRadius | ✅ OK | Squared distance math. |
| `allow %livingentities% to (duplicate\|clone)` | Effect | EffAllayCanDuplicate | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (duplicate\|clone)` | Effect | EffAllayDuplicate | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `apply [%-number%] bone[ ]meal[s] [to %blocks%]` | Effect | EffApplyBoneMeal | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `update %blocks% (as\|to be) %blockdata% [physics:without [neighbo[u...` | Effect | EffBlockUpdate | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `break %blocks% [naturally] [using %-itemtype%]` | Effect | EffBreakNaturally | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(cancel\|ignore) [the] [current] [command] cooldown` | Effect | EffCancelCooldown | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(cancel\|clear\|delete) [the] drops [of (items:items\|xp:[e]xp[erie...` | Effect | EffCancelDrops | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(cancel\|interrupt) [the] us[ag]e of %livingentities%'[s] [active\|...` | Effect | EffCancelItemUse | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %entities% [un:(un\|not \|non[-\| ])](charged\|powered)` | Effect | EffCharge | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(clear\|empty) the (stored entities\|entity storage) of %blocks%` | Effect | EffClearEntityStorage | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(dye\|colo[u]r\|paint) %itemtypes% %color%` | Effect | EffColorItems | ✅ OK | Item meta update is required. |
| `make command block[s] %blocks% [not:(un\|not )]conditional` | Effect | EffCommandBlockConditional | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `connect %players% to [proxy\|bungeecord] [server] %string%` | Effect | EffConnect | ✅ OK | Rare, network-bound. |
| `copy %~objects% [in]to %~objects%` | Effect | EffCopy | ✅ OK | Deep copy is the required work. |
| `(:show\|hide) [the] (custom\|display)[ ]name of %entities%` | Effect | EffCustomName | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (start dancing\|dance) [%-direction% %-locati...` | Effect | EffDancing | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `detonate %entities%` | Effect | EffDetonate | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `<.+> if <.+>` | Effect | EffDoIf | ✅ OK | Two field writes per run. |
| `(force\|allow) [the] (lead\|leash) [item] to drop` | Effect | EffDropLeash | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (:start\|stop) eating` | Effect | EffEating | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(prevent\|disallow) [the] (boosting\|used) firework from being cons...` | Effect | EffElytraBoostConsume | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (randomly teleport\|teleport randomly)` | Effect | EffEndermanTeleport | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `[:un]enforce [the] [server] white[ ]list` | Effect | EffEnforceWhitelist | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% despawn[able] (on chunk unload\|when far away)` | Effect | EffEntityUnload | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `hide %entities% [(from\|for) %-players%]` | Effect | EffEntityVisibility | ✅ OK | Re-evaluates the entity list per player; kept, because evaluating it once would change results for random expressions. |
| `equip [%livingentities%] with %itemtypes%` | Effect | EffEquip | ✅ OK | Equipment calls in the server dominate. |
| `cause exception` | Effect | EffExceptionDebug | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `instantly explode [creeper[s]] %livingentities%` | Effect | EffExplodeCreeper | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `[(create\|make)] [an] explosion (of\|with) (force\|strength\|power)...` | Effect | EffExplosion | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `feed [the] %players% [by %-number% [beef[s]]]` | Effect | EffFeed | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %itemtypes% [:not] (fire resistant\|resistant to fire)` | Effect | EffFireResistant | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(launch\|deploy) [[a] firework [with effect[s]]] %fireworkeffects% ...` | Effect | EffFireworkLaunch | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% attack %entities%` | Effect | EffForceAttack | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %blocks/itemtypes% have glowing text` | Effect | EffGlowingText | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `remove [the] (left horn[s]\|right:right horn[s]\|both:both horns) o...` | Effect | EffGoatHorns | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% ram %livingentity%` | Effect | EffGoatRam | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (:left\|right)( \|-)handed` | Effect | EffHandedness | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `hide %players% (in\|on\|from) [the] server list` | Effect | EffHidePlayerFromServerList | ✅ OK | Rare (server list pings). |
| `(ignite\|set fire to) %entities% [for %-timespan%]` | Effect | EffIgnite | ✅ OK | One setter per entity. |
| `make %entities% [(1¦not)] incendiary` | Effect | EffIncendiary | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(add\|insert) %livingentities% [in[ ]]to [the] (stored entities\|en...` | Effect | EffInsertEntityStorage | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (invisible\|not visible)` | Effect | EffInvisible | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %entities% (invulnerable\|invincible)` | Effect | EffInvulnerability | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(prevent\|disallow) %itementities% from (naturally despawning\|desp...` | Effect | EffItemDespawn | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `keep [the] (inventory\|items) [(1:and [e]xp[erience][s] [point[s]])]` | Effect | EffKeepInventory | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(apply knockback to\|knock[back]) %livingentities% [%direction%] [w...` | Effect | EffKnockback | ✅ OK | Server knockback call. |
| `(leash\|lead) %entities% to %entity%` | Effect | EffLeash | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(open\|:close) [the] lid[s] (of\|for) %blocks%` | Effect | EffLidState | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(create\|strike) lightning(1¦[ ]effect\|) %directions% %locations%` | Effect | EffLightning | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `load [the] server icon (from\|of) [the] [image] [file] %string%` | Effect | EffLoadServerIcon | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(force\|make) %livingentities% [to] (face [towards]\|look [(at\|tow...` | Effect | EffLook | ✅ OK | Server look call. |
| `make [the] egg [:not] hatch` | Effect | EffMakeEggHatch | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `force %players% to [(start\|1¦stop)] fly[ing]` | Effect | EffMakeFly | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %players% (say\|send [the] message[s]) %strings%` | Effect | EffMakeSay | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `[de[-]]op %offlineplayers%` | Effect | EffOp | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(open\|show) book %itemtype% (to\|for) %players%` | Effect | EffOpenBook | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `unchecked` | Effect | EffOpenInventory | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% get (:on\|off) (its\|their) back[s]` | Effect | EffPandaOnBack | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (start:(start rolling\|roll)\|stop rolling)` | Effect | EffPandaRolling | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (start:(start sneezing\|sneeze)\|stop sneezing)` | Effect | EffPandaSneezing | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (pathfind\|move) to[wards] %livingentity/loca...` | Effect | EffPathfind | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %entities/blocks% [:not] persist[ent]` | Effect | EffPersistent | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `hide [all] player [related] info[rmation] [(in\|on\|from) [the] ser...` | Effect | EffPlayerInfoVisibility | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `make %livingentities% (start playing\|play) dead` | Effect | EffPlayingDead | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(push\|thrust\|pull) %entities% [along] %direction% [(at\|with) [a]...` | Effect | EffPush | ✅ OK | Vector math per entity. |
| `enable PvP [in %worlds%]` | Effect | EffPvP | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(release\|evict) [the] (stored entities\|entity storage) of %blocks...` | Effect | EffReleaseEntityStorage | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `replace [(all\|every)\|first:[the] first] %strings% in %strings% wi...` | Effect | EffReplace | ⚡ Optimized | 'replace all regex ... in {_x}' compiled every pattern on every run; the patterns of the last needles are now reused (same change as the replace expression). |
| `force %players% to respawn` | Effect | EffRespawn | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `ring %blocks% [from [the]] [%-direction%]` | Effect | EffRing | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `run %executable% [arguments:with arg[ument]s %-objects%]` | Effect | EffRun | ✅ OK | Cost is the called function. |
| `make %livingentities% (start screaming\|scream)` | Effect | EffScreaming | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `(1:(enable\|load)\|2:reload\|3:disable\|4:unload) script [file\|nam...` | Effect | EffScriptFile | ✅ OK | Script loading dominates. |
| `make %players% see %locations% as %itemtype/blockdata%` | Effect | EffSendBlockChange | ✅ OK | Packet sending dominates. |
| `send [the] resource pack [from [[the] URL]] %string% to %players%` | Effect | EffSendResourcePack | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `[:force] ` | Effect | EffShear | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `silence %entities%` | Effect | EffSilence | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |
| `sort %~objects% [in (:descending\|ascending) order] [(by\|based on)...` | Effect | EffSort | ✅ OK | Sorting is the required work; comparator lookups were made lock-free earlier. |
| `make %players% (start sprinting\|sprint)` | Effect | EffSprinting | ✅ OK | Thin wrapper around a server call (entity/block/player action); the server work dominates. |

## Section 6

effects, expressions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `(stop\|shut[ ]down) [the] server` | Effect | EffStopServer | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `stop (all:all sound[s]\|sound[s] %-strings%) [(in [the]\|from) %-so...` | Effect | EffStopSound | ✅ OK | Sound lookup per name. |
| `make %livingentities% start shivering` | Effect | EffStriderShivering | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[stop:un]suppress [local variable] type hints` | Effect | EffSuppressTypeHints | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[local[ly]] suppress [the] (` | Effect | EffSuppressWarnings | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `make %livingentities% swing [their] [main] hand` | Effect | EffSwingHand | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[:un](tame\|domesticate) %entities%` | Effect | EffTame | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `Cannot toggle '` | Effect | EffToggle | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `allow %livingentities% to pick([ ]up items\| items up)` | Effect | EffToggleCanPickUpItems | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(allow\|enable) (fly\|flight) (for\|to) %players%` | Effect | EffToggleFlight | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(show\|reveal\|:hide) %itemtypes%'[s] [entire\|:additional] tool[ ]tip` | Effect | EffTooltip | ✅ OK | Item meta update is required. |
| `(transform\|map) %~objects% (using\|with) <.+>` | Effect | EffTransform | ✅ OK | Mapping is the required work. |
| `(grow\|create\|generate) tree [of type %treetype%] %directions% %lo...` | Effect | EffTree | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(make\|let\|force) %entities% [to] (ride\|mount) [(in\|on)] %entity...` | Effect | EffVehicle | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `make %livingentities% (start sleeping\|[go to] sleep) [%-direction%...` | Effect | EffWakeupSleep | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `make %livingentities% sense [a] disturbance %direction% %location%` | Effect | EffWardenDisturbance | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `load [the] world[s] %strings% [with environment %-environment%]` | Effect | EffWorldLoad | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `save [[the] world[s]] %worlds%` | Effect | EffWorldSave | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `zombify %livingentities%` | Effect | EffZombify | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] absorbed blocks` | Expression | ExprAbsorbedBlocks | ✅ OK | Rare event. |
| `(raised\|active) (tool\|item\|weapon)` | Expression | ExprActiveItem | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] affected entities` | Expression | ExprAffectedEntities | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[:max[imum]] age` | Expression | ExprAge | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(ai\|artificial intelligence)` | Expression | ExprAI | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `target jukebox` | Expression | ExprAllayJukebox | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[all [[of] the]\|the] banned (players\|ips:(ips\|ip addresses))` | Expression | ExprAllBannedEntries | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[(all\|the\|all [of] the)] [registered] [(1¦script)] commands` | Expression | ExprAllCommands | ⚡ Optimized | 'all commands' collected the labels with a parallel stream (shared thread pool) per call; now a plain loop with the same order. |
| `alphabetically sorted %strings%` | Expression | ExprAlphabetList | ✅ OK | Sorting is the required work. |
| `altitude[s]` | Expression | ExprAltitude | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] (amount\|number) of %itemtypes% (in\|of) %inventories%` | Expression | ExprAmountOfItems | ⚡ Optimized | 'amount of <items> in <inventory>' built a new item type for every stack once per item type checked; now once per stack. |
| `%number% [in] deg[ree][s]` | Expression | ExprAngle | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[anvil] [item] [:max[imum]] repair cost` | Expression | ExprAnvilRepairCost | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `anvil [inventory] (rename\|text) input` | Expression | ExprAnvilText | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(any [one]\|one) of [the] %objects%` | Expression | ExprAnyOf | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] applied [beacon] effect` | Expression | ExprAppliedEffect | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(:alpha\|:red\|:green\|:blue) (value\|component)` | Expression | ExprARGB | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(old\|unequipped) armo[u]r item` | Expression | ExprArmorChangeItem | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(%-*equipmentslots%\|[the] armo[u]r[s]) [item:item[s]]` | Expression | ExprArmorSlot | ✅ OK | Stream per call, but only a few slots per entity. |
| `arrow knockback strength` | Expression | ExprArrowKnockbackStrength | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `arrow pierce level` | Expression | ExprArrowPierceLevel | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[number of] arrow[s] stuck in %livingentities%` | Expression | ExprArrowsStuck | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(attached\|hit) block[multiple:s]` | Expression | ExprAttachedBlock | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `attack cooldown` | Expression | ExprAttackCooldown | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] (attacked\|damaged\|victim) [<(.+)>]` | Expression | ExprAttacked | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[a[n]] %*bannerpatterntypes% item[s]` | Expression | ExprBannerItem | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[all [[of] the]\|the] banner pattern[s] of %itemstacks/itemtypes/sl...` | Expression | ExprBannerPatterns | ✅ OK | Block state read is required. |
| `[the] [piglin] barter[ing] drops` | Expression | ExprBarterDrops | ✅ OK | Rare event. |
| `[the] [piglin] barter[ing] input` | Expression | ExprBarterInput | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(:primary\|secondary) [beacon] effect` | Expression | ExprBeaconEffects | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `beacon [effect] range` | Expression | ExprBeaconRange | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `beacon tier` | Expression | ExprBeaconTier | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[(safe:(safe\|valid)\|(unsafe\|invalid))] bed[s] [location[s]]` | Expression | ExprBed | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `target flower` | Expression | ExprBeehiveFlower | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[max:max[imum]] honey level` | Expression | ExprBeehiveHoneyLevel | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] biome [(of\|%direction%) %locations%]` | Expression | ExprBiome | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `block[ ]data` | Expression | ExprBlockData | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[block] hardness` | Expression | ExprBlockHardness | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[(all [[of] the]\|the)] blocks %direction% [%locations%]` | Expression | ExprBlocks | ✅ OK | Block iteration is the required work. |
| `(1:break\|2:fall\|3:hit\|4:place\|5:step) sound[s]` | Expression | ExprBlockSound | ✅ OK | Rare. |
| `[(all [[of] the]\|the)] blocks in radius %number% [(of\|around) %lo...` | Expression | ExprBlockSphere | ✅ OK | Lazy iterator. |
| `[the] break speed[s] [of %blocks%] [for %players%]` | Expression | ExprBreakSpeed | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `(brushable\|buried) item` | Expression | ExprBrushableItem | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `carr(ied\|ying) block[[ ]data]` | Expression | ExprCarryingBlockData | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] last caught [run[ ]time] errors` | Expression | ExprCaughtErrors | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `character (from\|at\|with) code([ ]point\| position) %integer%` | Expression | ExprCharacterFromCodepoint | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[(all [[of] the]\|the)] [:alphanumeric] characters (between\|from) ...` | Expression | ExprCharacters | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[:max[imum]] charge[s]` | Expression | ExprCharges | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[a] [new] chest inventory (named\|with name) %textcomponent% [with ...` | Expression | ExprChestInventory | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[(all [[of] the]\|the)] chunk[s] (of\|%-directions%) %locations%` | Expression | ExprChunk | ✅ OK | Chunk lookups in the server. |
| `[the] (` | Expression | ExprClicked | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `client view distance[s]` | Expression | ExprClientViewDistance | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] remaining [time] [of [the] (cooldown\|wait) [(of\|for) [the] ...` | Expression | ExprCmdCooldownInfo | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[unicode\|character] code([ ]point\| position)` | Expression | ExprCodepoint | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[command[ ]block] command` | Expression | ExprCommandBlockCommand | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] main command [label\|name] [of [[the] command[s] %-strings%]]` | Expression | ExprCommandInfo | ✅ OK | Rare. |
| `[command['s]] (sender\|executor)` | Expression | ExprCommandSender | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `compass target` | Expression | ExprCompassTarget | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] [skript] config` | Expression | ExprConfig | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[the] consumed item` | Expression | ExprConsumedItem | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |
| `[creeper] max[imum] fuse tick[s]` | Expression | ExprCreeperMaxFuseTicks | ✅ OK | Thin wrapper around a server getter/setter (block state, entity or item meta access dominates). |

## Section 7

expressions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `cursor slot` | Expression | ExprCursorSlot | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[custom] model data` | Expression | ExprCustomModelData | ✅ OK | Item meta read is required. |
| `[the] damage` | Expression | ExprDamage | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `damage cause` | Expression | ExprDamageCause | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%itemtype% with (damage\|data) [value] %number%` | Expression | ExprDamagedItem | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `debug info[rmation]` | Expression | ExprDebugInfo | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(de\|un)queued %queue%` | Expression | ExprDequeuedQueue | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `difference (between\|of) %object% and %object%` | Expression | ExprDifference | ✅ OK | Difference lookups are cached. |
| `difficult(y\|ies)` | Expression | ExprDifficulty | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[%-number% [(block\|met(er\|re))[s]] [to the]] (` | Expression | ExprDirection | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[:max[imum]] domestication level` | Expression | ExprDomestication | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] drops` | Expression | ExprDrops | ✅ OK | One item type per drop, required by the return type. |
| `[(all\|the\|all [of] the)] drops of %blocks% [(using\|with) %-itemt...` | Expression | ExprDropsOfBlock | ✅ OK | Drop calculation in the server dominates. |
| `(duplicat(e\|ing\|ion)\|clon(e\|ing)) cool[ ]down [time]` | Expression | ExprDuplicateCooldown | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(damage[s] [value[s]]\|1:durabilit(y\|ies))` | Expression | ExprDurability | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[:max[imum]] (dust\|brush)[ed\|ing] (value\|stage\|progress[ion])` | Expression | ExprDustedStage | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[thrown] egg` | Expression | ExprEgg | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `ender[ ]chest[s]` | Expression | ExprEnderChest | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [event-]<.+>` | Expression | ExprEntity | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] %attributetype% [(1:(total\|final\|modified))] attribute [val...` | Expression | ExprEntityAttribute | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[elapsed\|:remaining] (item\|tool) us[ag]e time` | Expression | ExprEntityItemUseTime | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] (owner\|tamer) of %livingentities%` | Expression | ExprEntityOwner | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `entity size` | Expression | ExprEntitySize | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `entity snapshot` | Expression | ExprEntitySnapshot | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `unchecked` | Expression | ExprEntitySound | ✅ OK | Rare. |
| `[max:max[imum]] [stored] entity count` | Expression | ExprEntityStorageEntityCount | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [event-]initiator[( \|-)inventory]` | Expression | ExprEvtInitiator | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `exact item[s]` | Expression | ExprExactItem | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%objects% (except\|excluding\|not including) %objects%` | Expression | ExprExcept | ✅ OK | Comparisons are the required work. |
| `exhaustion` | Expression | ExprExhaustion | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] (spawned\|dropped\|) [e]xp[erience] [orb[s]]` | Expression | ExprExperience | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(experience\|[e]xp) [pickup\|collection] cooldown` | Expression | ExprExperienceCooldown | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(experience\|[e]xp) cooldown change (reason\|cause\|type)` | Expression | ExprExperienceCooldownChangeReason | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] exploded blocks` | Expression | ExprExplodedBlocks | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [explosion['s]] block (yield\|amount)` | Expression | ExprExplosionBlockYield | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] explosion (yield\|radius\|size)` | Expression | ExprExplosionYield | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `explosive (yield\|radius\|size\|power)` | Expression | ExprExplosiveYield | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(head\|eye[s]) [location[s]]` | Expression | ExprEyeLocation | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(1¦horizontal\|) facing` | Expression | ExprFacing | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `fall[en] (distance\|height)` | Expression | ExprFallDistance | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[all] [the] fertilized blocks` | Expression | ExprFertilizedBlocks | ✅ OK | Rare event. |
| `[the] final damage` | Expression | ExprFinalDamage | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[:max[imum]] (burn[ing]\|fire) (time\|duration)` | Expression | ExprFireTicks | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(1¦\|2¦flickering\|3¦trailing\|4¦flickering trailing\|5¦trailing fl...` | Expression | ExprFireworkEffect | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `first empty slot[s]` | Expression | ExprFirstEmptySlot | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `fl(y[ing]\|ight) (mode\|state)` | Expression | ExprFlightMode | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] (food\|hunger)[[ ](level\|met(er\|re)\|bar)] [of %players%]` | Expression | ExprFoodLevel | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%dates% formatted [human-readable] [(with\|as) %-string%]` | Expression | ExprFormatDate | ⚡ Optimized | With a non-literal format (e.g. from a variable), parsed the pattern into a new SimpleDateFormat on every call. The last format is now kept per thread (SimpleDateFormat isn't thread-safe) and reused while the pattern is the same; literal formats were already parsed once. |
| `freeze time` | Expression | ExprFreezeTicks | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[:offline[ ]]player[s] from %uuids%` | Expression | ExprFromUUID | ✅ OK | Server lookups. |
| `[the\|a] function [named] %string% [(in\|from) %-script%]` | Expression | ExprFunction | ✅ OK | Function lookup by name. |
| `[the] gamerule %gamerule% of %worlds%` | Expression | ExprGameRule | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(gliding\|glider) [state]` | Expression | ExprGlidingState | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `glowing` | Expression | ExprGlowing | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `gravity` | Expression | ExprGravity | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] hanging (entity\|:remover)` | Expression | ExprHanging | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%strings% hash[ed] with (:(MD5\|SHA-256\|SHA-384\|SHA-512))` | Expression | ExprHash | ✅ OK | Hashing is the required work. |
| `[the] hatching number` | Expression | ExprHatchingNumber | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] hatching entity [type]` | Expression | ExprHatchingType | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] heal[ing] amount` | Expression | ExprHealAmount | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(regen\|health regain\|heal[ing]) (reason\|cause)` | Expression | ExprHealReason | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[(all [[of] the]\|the)] hidden players (of\|for) %players%` | Expression | ExprHiddenPlayers | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] (host\|domain)[ ][name]` | Expression | ExprHostname | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] hotbar button` | Expression | ExprHotbarButton | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[([current:currently] selected\|current:current)] hotbar slot[s]` | Expression | ExprHotbarSlot | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [custom] [player\|server] (hover\|sample) ([message] list\|me...` | Expression | ExprHoverList | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `humidit(y\|ies)` | Expression | ExprHumidity | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [1:first\|2:last\|3:all] (position[mult:s]\|mult:indices\|ind...` | Expression | ExprIndicesOfValue | ✅ OK | Search is the required work. |
| `input` | Expression | ExprInput | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `inventory action` | Expression | ExprInventoryAction | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] inventory clos(e\|ing) (reason\|cause)` | Expression | ExprInventoryCloseReason | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(` | Expression | ExprInventoryInfo | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] slot[s] %numbers% of %inventory%` | Expression | ExprInventorySlot | ✅ OK | Slot wrappers only. |
| `[the] (inverse\|opposite)[s] of %booleans%` | Expression | ExprInverse | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `IP[s][( \|-)address[es]] of %players%` | Expression | ExprIP | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `item` | Expression | ExprItem | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [item] cooldown of %itemtypes% for %players%` | Expression | ExprItemCooldown | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `item flags` | Expression | ExprItemFlags | ✅ OK | Item meta read is required. |
| `[the] uuid of [the] [dropped] item owner [of %itementities%]` | Expression | ExprItemOwner | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[all [[of] the]\|the] block[[ ]type]s` | Expression | ExprItems | ✅ OK | Literal results are already buffered. |

## Section 8

expressions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `[the] uuid of [the] [dropped] item thrower [of %itementities%]` | Expression | ExprItemThrower | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%itemtype% with [custom] model data %numbers/booleans/strings/colors%` | Expression | ExprItemWithCustomModelData | ✅ OK | Item clone + meta change is required. |
| `%itemtypes% with[:out] [entire\|:additional] tool[ ]tip[s]` | Expression | ExprItemWithTooltip | ✅ OK | Item clone + meta change is required. |
| `(keyed\|indexed) %~objects%` | Expression | ExprKeyed | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[([currently] selected\|current)] [game] (language\|locale) [setting]` | Expression | ExprLanguage | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `last attacker` | Expression | ExprLastAttacker | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `last damage` | Expression | ExprLastDamage | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `last damage (cause\|reason\|type)` | Expression | ExprLastDamageCause | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[last] death location[s]` | Expression | ExprLastDeathLocation | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [last[ly]] loaded server icon` | Expression | ExprLastLoadedServerIcon | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(1¦last\|2¦first) login` | Expression | ExprLastLoginTime | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[last] resource pack response[s]` | Expression | ExprLastResourcePackResponse | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [last[ly]] (0:spawned\|1:shot) %*entitydata%` | Expression | ExprLastSpawnedEntity | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `leash holder[s]` | Expression | ExprLeashHolder | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[xp\|exp[erience]] level` | Expression | ExprLevel | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `level progress` | Expression | ExprLevelProgress | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[(1¦sky\|1¦sun\|2¦block)[ ]]light[ ]level [(of\|%direction%) %locat...` | Expression | ExprLightLevel | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] (location\|position) [at] [\\(][x[ ][=[ ]]]%number%, [y[ ][=[...` | Expression | ExprLocationAt | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%vector% to location in %world%` | Expression | ExprLocationFromVector | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(location\|position) of %location%` | Expression | ExprLocationOf | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%location% offset by [[the] vectors] %vectors% [facingrelative:usin...` | Expression | ExprLocationVectorOffset | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] (highest\|:lowest) [solid] block (at\|of) %locations%` | Expression | ExprLowestHighestSolidBlock | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `max[imum] (durabilit(y\|ies)\|damage)` | Expression | ExprMaxDurability | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `max[imum] freeze time` | Expression | ExprMaxFreezeTicks | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `max[imum] health` | Expression | ExprMaxHealth | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `max[imum] [item] us(e\|age) (time\|duration)` | Expression | ExprMaxItemUseTime | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `max[imum] minecart (speed\|velocity)` | Expression | ExprMaxMinecartSpeed | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [1:(real\|default)\|2:(fake\|shown\|displayed)] max[imum] pla...` | Expression | ExprMaxPlayers | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `max[imum] stack[[ ]size]` | Expression | ExprMaxStack | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `me` | Expression | ExprMe | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [server] (:free\|max:max[imum]\|total) (memory\|ram)` | Expression | ExprMemory | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [mending] repair amount` | Expression | ExprMendingRepairAmount | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(middle\|center) [point]` | Expression | ExprMiddleOfLocation | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] mid[-]point (of\|between) %object% and %object%` | Expression | ExprMidpoint | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[minecart] (1¦derailed\|2¦flying) velocity` | Expression | ExprMinecartDerailedFlyingVelocity | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(lunar\|moon) phase[s]` | Expression | ExprMoonPhase | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%itemtype/inventorytype% (named\|with name[s]) %textcomponent%` | Expression | ExprNamed | ✅ OK | Item clone + meta change is required (the name component is already templated). |
| `[the] (nearest\|closest) %*entitydatas% [[relative] to %entity/loca...` | Expression | ExprNearestEntity | ✅ OK | Entity search in the server. |
| `[a] %bannerpatterntype% colo[u]red %color%` | Expression | ExprNewBannerPattern | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(invulnerability\|invincibility\|no damage) tick[s]` | Expression | ExprNoDamageTicks | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(invulnerability\|invincibility\|no damage) time[[ ]span]` | Expression | ExprNoDamageTime | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] node %string% (of\|in) %node%` | Expression | ExprNode | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `number of upper[ ]case char(acters\|s) in %string%` | Expression | ExprNumberOfCharacters | ⚡ Optimized | Counted characters through a boxed iterator over code points (an Integer per character); now a plain loop over the same code points. |
| `[(all [[of] the]\|the)] offline[ ]players` | Expression | ExprOfflinePlayers | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [(1:(real\|default)\|2:(fake\|shown\|displayed))] [online] pl...` | Expression | ExprOnlinePlayersCount | ✅ OK | One getter. |
| `[the] (current\|open\|top) inventory [of %players%]` | Expression | ExprOpenedInventory | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[all [[of] the]\|the] [server] [:non(-\| )]op[erator]s` | Expression | ExprOps | ✅ OK | Rare. |
| `(:main\|hidden) gene[s]` | Expression | ExprPandaGene | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [last] [parse] error` | Expression | ExprParseError | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] passenger[s] of %entities%` | Expression | ExprPassenger | ✅ OK | Passenger lookups. |
| `%number%(\\%\| percent) of %numbers%` | Expression | ExprPercent | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[(all [[of] the]\|the)] permissions (from\|of) %players%` | Expression | ExprPermissions | ✅ OK | Permission collection is the required work. |
| `pick[ ]up delay` | Expression | ExprPickupDelay | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[a[n]] (plain\|unmodified) %itemtype%` | Expression | ExprPlain | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[custom] chat completion[s]` | Expression | ExprPlayerChatCompletions | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `protocol version` | Expression | ExprPlayerProtocolVersion | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[(all [[of] the]\|the)] [loaded] plugins` | Expression | ExprPlugins | ✅ OK | Rare. |
| `[the] portal['s] blocks` | Expression | ExprPortal | ✅ OK | Rare event. |
| `portal cooldown` | Expression | ExprPortalCooldown | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(projectile\|arrow) critical (state\|ability\|mode)` | Expression | ExprProjectileCriticalState | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] projectile force` | Expression | ExprProjectileForce | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] [server] [(sent\|required\|fake)] protocol version [number]` | Expression | ExprProtocolVersion | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] moved blocks` | Expression | ExprPushedBlocks | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[a] [new] queue [(of\|with) %-objects%]` | Expression | ExprQueue | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(:start\|end)` | Expression | ExprQueueStartEnd | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(quit\|disconnect) (cause\|reason)` | Expression | ExprQuitReason | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[a\|%-integer%] random [:alphanumeric] character[s] (from\|between)...` | Expression | ExprRandomCharacter | ✅ OK | Uses ThreadLocalRandom. |
| `[a] random uuid` | Expression | ExprRandomUUID | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `(raw\|minecraft\|vanilla) name[s] of %itemtypes%` | Expression | ExprRawName | ✅ OK | Rare. |
| `[the] (readied\|selected\|drawn) (:arrow\|bow)` | Expression | ExprReadiedArrow | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `recursive %~objects%` | Expression | ExprRecursive | ✅ OK | Cost is the source expression. |
| `redstone power` | Expression | ExprRedstoneBlockPower | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%objects% (reduced\|folded) (to\|with\|by) \\[<.+>\\]` | Expression | ExprReduce | ✅ OK | Cost is the reducing expression. |
| `[the] reduced value` | Expression | ExprReducedValue | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `remaining air` | Expression | ExprRemainingAir | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `%strings% repeated %integer% time[s]` | Expression | ExprRepeat | ✅ OK | String building is the required work. |
| `resonat(e\|ing) time` | Expression | ExprResonatingTime | ⚡ Optimized | Took a full block state snapshot just to check the type, then a second state; now one non-snapshot state. |
| `respawn[ing] reason` | Expression | ExprRespawnReason | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `[the] result[plural:s] of [running\|executing] %executable% [argume...` | Expression | ExprResult | ✅ OK | Thin wrapper around a server getter/setter or event value. |
| `reversed %objects%` | Expression | ExprReversedList | ✅ OK | Reversal is the required work. |

## Section 9

expressions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `ring[ing] time` | Expression | ExprRingingTime | ⏳ |  |
| `saturation` | Expression | ExprSaturation | ⏳ |  |
| `[(all [[of] the]\|the)] scoreboard tags of %entities%` | Expression | ExprScoreboardTags | ⏳ |  |
| `[the] [current] script` | Expression | ExprScript | ⏳ |  |
| `[all [[of] the]\|the] scripts` | Expression | ExprScripts | ⏳ |  |
| `[all [of the]\|the] scripts [1:without ([subdirectory] paths\|paren...` | Expression | ExprScriptsOld | ⏳ |  |
| `sea level` | Expression | ExprSeaLevel | ⏳ |  |
| `[:(min\|max)[imum]] [sea] pickle(s\| (count\|amount))` | Expression | ExprSeaPickles | ⏳ |  |
| `[the] seed[s] (from\|of) %worlds%` | Expression | ExprSeed | ⏳ |  |
| `[the] [sent] [server] command[s] list` | Expression | ExprSentCommands | ⏳ |  |
| `[the] [(1¦(default)\|2¦(shown\|sent))] [server] icon` | Expression | ExprServerIcon | ⏳ |  |
| `[the] shooter [of %projectile%]` | Expression | ExprShooter | ⏳ |  |
| `shuffled %objects%` | Expression | ExprShuffledList | ⏳ |  |
| `simulation distance[s]` | Expression | ExprSimulationDistance | ⏳ |  |
| `skull` | Expression | ExprSkull | ⏳ |  |
| `(head\|skull) owner` | Expression | ExprSkullOwner | ⏳ |  |
| `[raw:(raw\|unique)] index` | Expression | ExprSlotIndex | ⏳ |  |
| `[the] source block` | Expression | ExprSourceBlock | ⏳ |  |
| `[the] spawn[s] [(point\|location)[s]] [of %worlds%]` | Expression | ExprSpawn | ⏳ |  |
| `spawn egg entity` | Expression | ExprSpawnEggEntity | ⏳ |  |
| `(spawner\|entity\|creature) type[s]` | Expression | ExprSpawnerType | ⏳ |  |
| `spawn[ing] reason` | Expression | ExprSpawnReason | ⏳ |  |
| `spectator target [of %-players%]` | Expression | ExprSpectatorTarget | ⏳ |  |
| `(0¦walk[ing]\|1¦fl(y[ing]\|ight))[( \|-)]speed` | Expression | ExprSpeed | ⏳ |  |
| `[the] %*classinfo% value [at] %string% (from\|in) %node%` | Expression | ExprSubnodeValue | ⏳ |  |
| `(tablist[ed]\|listed) players` | Expression | ExprTablistedPlayers | ⏳ |  |
| `[the] tamer` | Expression | ExprTamer | ⏳ |  |
| `[the] target[[ed] %-*entitydata%] [of %livingentities%] [blocks:ign...` | Expression | ExprTarget | ⏳ |  |
| `teleport (cause\|reason\|type)` | Expression | ExprTeleportCause | ⏳ |  |
| `temperature[s]` | Expression | ExprTemperature | ⏳ |  |
| `[the] time[s] [([with]in\|of) %worlds%]` | Expression | ExprTime | ⏳ |  |
| `time (alive\|lived)` | Expression | ExprTimeLived | ⏳ |  |
| `(time played\|play[ ]time)` | Expression | ExprTimePlayed | ⏳ |  |
| `%number% time[s]` | Expression | ExprTimes | ⏳ |  |
| `(:(tick\|second\|minute\|hour\|day\|week\|month\|year))s` | Expression | ExprTimespanDetails | ⏳ |  |
| `[the] (former\|past\|old) [state] [of] %~objects%` | Expression | ExprTimeState | ⏳ |  |
| `[total] experience` | Expression | ExprTotalExperience | ⏳ |  |
| `tps from [the] last ([1] minute\|1[ ]m[inute])` | Expression | ExprTPS | ⏳ |  |
| `%objects% (transformed\|mapped) (using\|with) \\[<.+>\\]` | Expression | ExprTransform | ⏳ |  |
| `[the] transform[ing] (cause\|reason\|type)` | Expression | ExprTransformReason | ⏳ |  |
| `type` | Expression | ExprTypeOf | ⏳ |  |
| `[:un]breakable %itemtypes%` | Expression | ExprUnbreakable | ⏳ |  |
| `unix date` | Expression | ExprUnixDate | ⏳ |  |
| `unix timestamp` | Expression | ExprUnixTicks | ⏳ |  |
| `[the] unleash[ing] reason` | Expression | ExprUnleashReason | ⏳ |  |
| `[the] %*classinfo% value of %valued%` | Expression | ExprValue | ⏳ |  |
| `[the] (%-*classinfo%\|value[:s]) (within\|in) %~objects%` | Expression | ExprValueWithin | ⏳ |  |
| `[the] angle between [[the] vectors] %vector% and %vector%` | Expression | ExprVectorAngleBetween | ⏳ |  |
| `[the] vector (from\|between) %location% (to\|and) %location%` | Expression | ExprVectorBetweenLocations | ⏳ |  |
| `%vector% cross %vector%` | Expression | ExprVectorCrossProduct | ⏳ |  |
| `[a] [new] cylindrical vector [from\|with] [radius] %number%, [yaw] ...` | Expression | ExprVectorCylindrical | ⏳ |  |
| `%vector% dot %vector%` | Expression | ExprVectorDotProduct | ⏳ |  |
| `vector[s] [from] %directions%` | Expression | ExprVectorFromDirection | ⏳ |  |
| `[a] [new] vector [(from\|at\|to)] %number%,[ ]%number%(,[ ]\| and )...` | Expression | ExprVectorFromXYZ | ⏳ |  |
| `[a] [new] vector (from\|with) yaw %number% and pitch %number%` | Expression | ExprVectorFromYawAndPitch | ⏳ |  |
| `(vector\|standard\|normal) length[s]` | Expression | ExprVectorLength | ⏳ |  |
| `normali(z\|s)e[d] %vector%` | Expression | ExprVectorNormalize | ⏳ |  |
| `[the] vector (of\|from\|to) %location%` | Expression | ExprVectorOfLocation | ⏳ |  |
| `[vector] projection [of] %vector% on[to] %vector%` | Expression | ExprVectorProjection | ⏳ |  |
| `[a] random vector` | Expression | ExprVectorRandom | ⏳ |  |
| `[a] [new] spherical vector [(from\|with)] [radius] %number%, [yaw] ...` | Expression | ExprVectorSpherical | ⏳ |  |
| `squared length[s]` | Expression | ExprVectorSquaredLength | ⏳ |  |
| `vehicle[s]` | Expression | ExprVehicle | ⏳ |  |
| `velocit(y\|ies)` | Expression | ExprVelocity | ⏳ |  |
| `(0¦[craft]bukkit\|1¦minecraft\|2¦skript)( \|-)version` | Expression | ExprVersion | ⏳ |  |
| `[the] [shown\|custom] version [string\|text]` | Expression | ExprVersionString | ⏳ |  |
| `view distance[s]` | Expression | ExprViewDistance | ⏳ |  |
| `villager (level\|:experience)` | Expression | ExprVillagerLevel | ⏳ |  |
| `villager profession` | Expression | ExprVillagerProfession | ⏳ |  |
| `villager type` | Expression | ExprVillagerType | ⏳ |  |
| `most angered entity` | Expression | ExprWardenAngryAt | ⏳ |  |
| `[the] anger level [of] %livingentities% towards %livingentities%` | Expression | ExprWardenEntityAnger | ⏳ |  |
| `[the] weather [(in\|of) %players/worlds%]` | Expression | ExprWeather | ⏳ |  |
| `whether <.+>` | Expression | ExprWhether | ⏳ |  |
| `[the] white[ ]list` | Expression | ExprWhitelist | ⏳ |  |
| `%itemtype% with[:out] fire[ ]resistance` | Expression | ExprWithFireResistance | ⏳ |  |
| `%itemtypes% with [the] item flag[s] %itemflags%` | Expression | ExprWithItemFlags | ⏳ |  |
| `[world] environment` | Expression | ExprWorldEnvironment | ⏳ |  |
| `[the] world [(named\|with name)] %string%` | Expression | ExprWorldFromName | ⏳ |  |
| `[(all [[of] the]\|the)] worlds` | Expression | ExprWorlds | ⏳ |  |

## Section 10

expressions, hooks.chat.expressions, hooks.economy.expressions, ...

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `%number% of %itemstacks/itemtypes/entitytypes/particles%` | Expression | ExprXOf | ⏳ |  |
| `[vector\|quaternion] (:w\|:x\|:y\|:z) [component[s]]` | Expression | ExprXYZComponent | ⏳ |  |
| `(:yaw\|pitch)` | Expression | ExprYawPitch | ⏳ |  |
| `[chat] (1:prefix\|2:suffix)` | Expression | ExprPrefixSuffix | ⏳ |  |
| `(money\|balance\|[bank] account)` | Expression | ExprBalance | ⏳ |  |
| `all groups` | Expression | ExprAllGroups | ⏳ |  |
| `group[plural:s]` | Expression | ExprGroup | ⏳ |  |
| `%players% (can\|(is\|are) allowed to) build %directions% %locations%` | Condition | CondCanBuild | ⏳ |  |
| `%offlineplayers% (is\|are) (0¦[a] member\|1¦[(the\|an)] owner) of [...` | Condition | CondIsMember | ⏳ |  |
| `[[the] region] %regions% contain[s] %directions% %locations%` | Condition | CondRegionContains | ⏳ |  |
| `[(all\|the)] blocks (in\|of) [[the] region[s]] %regions%` | Expression | ExprBlocksInRegion | ⏳ |  |
| `(all\|the\|) (0¦members\|1¦owner[s]) of [[the] region[s]] %regions%` | Expression | ExprMembersOfRegion | ⏳ |  |
| `[event-]region` | Expression | ExprRegion | ⏳ |  |
| `[the] region(1¦s\|) %direction% %locations%` | Expression | ExprRegionsAt | ⏳ |  |
| `at` | Literal | LitAt | ⏳ |  |
| `[the] (console\|server)` | Literal | LitConsole | ⏳ |  |
| `[the] max[imum] double value` | Literal | LitDoubleMaxValue | ⏳ |  |
| `[the] min[imum] double value` | Literal | LitDoubleMinValue | ⏳ |  |
| `[an] eternity` | Literal | LitEternity | ⏳ |  |
| `[the] max[imum] float value` | Literal | LitFloatMaxValue | ⏳ |  |
| `[the] min[imum] float value` | Literal | LitFloatMinValue | ⏳ |  |
| `positive (infinity\|∞) [value]` | Literal | LitInfinity | ⏳ |  |
| `[the] max[imum] integer value` | Literal | LitIntMaxValue | ⏳ |  |
| `[the] min[imum] integer value` | Literal | LitIntMinValue | ⏳ |  |
| `[the] max[imum] long value` | Literal | LitLongMaxValue | ⏳ |  |
| `[the] min[imum] long value` | Literal | LitLongMinValue | ⏳ |  |
| `NaN [value]` | Literal | LitNaN | ⏳ |  |
| `(-\|minus \|negative )(infinity\|∞) [value]` | Literal | LitNegativeInfinity | ⏳ |  |
| `nl` | Literal | LitNewLine | ⏳ |  |
| `(pi\|π)` | Literal | LitPi | ⏳ |  |
| `shoot %entitydatas% [from %livingentities/locations%] [(at\|with) (...` | Effect | EffSecShoot | ⏳ |  |
| `catch [run[ ]time] error[s]` | Section | SecCatchErrors | ⏳ |  |
| `filter %~objects% to match [:any\|all]` | Section | SecFilter | ⏳ |  |
| `aliases` | Structure | StructAliases | ⏳ |  |
| `auto[matically] reload [(this\|the) script]` | Structure | StructAutoReload | ⏳ |  |
| `example` | Structure | StructExample | ⏳ |  |
| `using [[the] experiment] <.+>` | Structure | StructUsing | ⏳ |  |
| `` | Expression | ExprFurnaceEventItems | ⏳ |  |
| `[the] ` | Expression | ExprFurnaceSlot | ⏳ |  |
| `[the] [furnace] ` | Expression | ExprFurnaceTime | ⏳ |  |
| `line %integer% [of %block%]` | Expression | ExprSignText | ⏳ |  |
| `unchecked` | Condition | CondHasBossBarFlag | ⏳ |  |
| `remove` | Effect | EffBossBarFlags | ⏳ |  |
| `boss[ ]bar` | Expression | ExprBossBarFromEntity | ⏳ |  |
| `[the] boss[ ]bar[s] (from\|with) [the] (id\|key)[s] %strings%` | Expression | ExprBossBarFromKey | ⏳ |  |
| `boss[ ]bar (key\|id)` | Expression | ExprKeyOfBossBar | ⏳ |  |
| `[a] [new] [%-color%] boss[ ]bar [(with title\|titled) %-textcompone...` | Expression | ExprSecCreateBossBar | ⏳ |  |
| `(age\|grow (up\|old[er]))` | Condition | CondCanAge | ⏳ |  |
| `(breed\|be bred)` | Condition | CondCanBreed | ⏳ |  |
| `[an] adult` | Condition | CondIsAdult | ⏳ |  |
| `a (child\|baby)` | Condition | CondIsBaby | ⏳ |  |
| `in lov(e\|ing) [state\|mode]` | Condition | CondIsInLove | ⏳ |  |
| `lock age of %livingentities%` | Effect | EffAllowAging | ⏳ |  |
| `make %livingentities% breedable` | Effect | EffBreedable | ⏳ |  |
| `make %livingentities% [a[n]] (:adult\|baby\|child)` | Effect | EffMakeAdultOrBaby | ⏳ |  |
| `[the] breeding mother` | Expression | ExprBreedingFamily | ⏳ |  |
| `love[d] time` | Expression | ExprLoveTime | ⏳ |  |
| `[the] brewing stand will consume [the] fuel` | Condition | CondBrewingConsume | ⏳ |  |
| `make [the] brewing stand consume [its\|the] fuel` | Effect | EffBrewingConsume | ⏳ |  |
| `brewing [stand] fuel (level\|amount)` | Expression | ExprBrewingFuelLevel | ⏳ |  |
| `[the] brewing results` | Expression | ExprBrewingResults | ⏳ |  |
| `[the] ` | Expression | ExprBrewingSlot | ⏳ |  |
| `[current\|remaining] brewing time` | Expression | ExprBrewingTime | ⏳ |  |
| `%damagesources% ((does\|do) scale\|scales) damage with difficulty` | Condition | CondScalesWithDifficulty | ⏳ |  |
| `%damagesources% (was\|were) ([:in]directly caused\|caused [:in]dire...` | Condition | CondWasIndirect | ⏳ |  |
| `(causing\|responsible) entity` | Expression | ExprCausingEntity | ⏳ |  |
| `created damage source` | Expression | ExprCreatedDamageSource | ⏳ |  |
| `damage location` | Expression | ExprDamageLocation | ⏳ |  |
| `damage type` | Expression | ExprDamageType | ⏳ |  |
| `direct entity` | Expression | ExprDirectEntity | ⏳ |  |
| `food exhaustion` | Expression | ExprFoodExhaustion | ⏳ |  |
| `[a] custom damage source [(with\|using) [the\|a] [damage type [of]]...` | Expression | ExprSecDamageSource | ⏳ |  |
| `source location` | Expression | ExprSourceLocation | ⏳ |  |
| `enchanted [with %-enchantmenttypes% [or (1:(better\|greater\|higher...` | Condition | CondIsEnchanted | ⏳ |  |
| `enchantment glint overrid(den\|e)` | Condition | CondItemEnchantmentGlint | ⏳ |  |
| `unchecked` | Effect | EffEnchant | ⏳ |  |
| `(force\|make) %itemtypes% [to] [start] glint[ing]` | Effect | EffForceEnchantmentGlint | ⏳ |  |
| `[the] applied enchant[ment]s` | Expression | ExprAppliedEnchantments | ⏳ |  |
| `[the] [displayed] ([e]xp[erience]\|enchanting) cost` | Expression | ExprEnchantingExpCost | ⏳ |  |
| `[the] enchant[:ed] item` | Expression | ExprEnchantItem | ⏳ |  |

## Section 11

bukkit.enchantments.elements.expressions, bukkit.entity.displays.elements.expressions, bukkit.entity.displays.item.elements.expressions, ...

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `[the] enchant[ment] bonus` | Expression | ExprEnchantmentBonus | ⏳ |  |
| `[the] enchant[ment] hint` | Expression | ExprEnchantmentHint | ⏳ |  |
| `[the] [enchant[ment]] level[s] of %enchantments% (on\|of) %itemtypes%` | Expression | ExprEnchantmentLevel | ⏳ |  |
| `[all [[of] the]\|the] enchant[ment] offers` | Expression | ExprEnchantmentOffer | ⏳ |  |
| `[enchant[ment]] cost` | Expression | ExprEnchantmentOfferCost | ⏳ |  |
| `enchantments` | Expression | ExprEnchantments | ⏳ |  |
| `%itemtypes% with[:out] [enchant[ment]] glint` | Expression | ExprItemWithEnchantmentGlint | ⏳ |  |
| `((:max\|min)[imum]\|starting) enchant[ment] level` | Expression | ExprMinMaxEnchantmentLevel | ⏳ |  |
| `stored enchant[ment]s` | Expression | ExprStoredEnchantments | ⏳ |  |
| `bill[ \|-]board[ing] [setting]` | Expression | ExprDisplayBillboard | ⏳ |  |
| `[:block\|:sky] (light [level]\|brightness) override[s]` | Expression | ExprDisplayBrightness | ⏳ |  |
| `glow[ing] colo[u]r[s] override[s]` | Expression | ExprDisplayGlowOverride | ⏳ |  |
| `display (:height\|width)` | Expression | ExprDisplayHeightWidth | ⏳ |  |
| `interpolation (:delay\|duration)[s]` | Expression | ExprDisplayInterpolation | ⏳ |  |
| `shadow (:radius\|strength)` | Expression | ExprDisplayShadow | ⏳ |  |
| `teleport[ation] duration[s]` | Expression | ExprDisplayTeleportDuration | ⏳ |  |
| `(:left\|right) [transformation] rotation` | Expression | ExprDisplayTransformationRotation | ⏳ |  |
| `(display\|[display] transformation) (:scale\|translation)` | Expression | ExprDisplayTransformationScaleTranslation | ⏳ |  |
| `[display] view (range\|radius)` | Expression | ExprDisplayViewRange | ⏳ |  |
| `item [display] transform` | Expression | ExprItemDisplayTransform | ⏳ |  |
| `[[the] text of] %displays% (has\|have) [a] (drop\|text) shadow` | Condition | CondTextDisplayHasDropShadow | ⏳ |  |
| `visible through (blocks\|walls)` | Condition | CondTextDisplaySeeThroughBlocks | ⏳ |  |
| `(apply\|add) (drop\|text) shadow to [[the] text of] %displays%` | Effect | EffTextDisplayDropShadow | ⏳ |  |
| `make %displays% visible through (blocks\|walls)` | Effect | EffTextDisplaySeeThroughBlocks | ⏳ |  |
| `text alignment[s]` | Expression | ExprTextDisplayAlignment | ⏳ |  |
| `line width` | Expression | ExprTextDisplayLineWidth | ⏳ |  |
| `[display] [text] opacity` | Expression | ExprTextDisplayOpacity | ⏳ |  |
| `[the] death( \|-)message` | Expression | ExprDeathMessage | ⏳ |  |
| `[the] path[ ]finding target location` | Expression | ExprPathfindingLocation | ⏳ |  |
| `[the] path[ ]finding target [entity]` | Expression | ExprPathfindingTarget | ⏳ |  |
| `(responsive\|:unresponsive)` | Condition | CondIsResponsive | ⏳ |  |
| `make %entities% responsive` | Effect | EffMakeResponsive | ⏳ |  |
| `interaction (height\|:width)[s]` | Expression | ExprInteractionDimensions | ⏳ |  |
| `[the] last (date\|time)[s] [that\|when] %entities% (were\|was) (att...` | Expression | ExprLastInteractionDate | ⏳ |  |
| `[the] last player[s] to (attack\|1:interact with\|2:click [on]) %en...` | Expression | ExprLastInteractionPlayer | ⏳ |  |
| `ban [kick:and kick] %strings/offlineplayers% [(by reason of\|becaus...` | Effect | EffBan | ⏳ |  |
| `kick %players% [(by reason of\|because [of]\|on account of\|due to)...` | Effect | EffKick | ⏳ |  |
| `[the] (message\|chat) format[ting]` | Expression | ExprChatFormat | ⏳ |  |
| `[the] [chat( \|-)]message` | Expression | ExprChatMessage | ⏳ |  |
| `[the] [chat( \| -)]recipients` | Expression | ExprChatRecipients | ⏳ |  |
| `[the] (join\|log[ ]in)( \|-)message` | Expression | ExprJoinMessage | ⏳ |  |
| `[the] kick( \|-)message` | Expression | ExprKickMessage | ⏳ |  |
| `[the] on-screen kick message` | Expression | ExprOnScreenKickMessage | ⏳ |  |
| `[the] picked (item\|1:block\|2:entity)` | Expression | ExprPickedItem | ⏳ |  |
| `(player\|tab)[ ]list (header\|:footer) [text\|message]` | Expression | ExprPlayerListHeaderFooter | ⏳ |  |
| `(player\|tab)[ ]list name[s]` | Expression | ExprPlayerListName | ⏳ |  |
| `(player\|tab)[ ]list priority` | Expression | ExprPlayerListPriority | ⏳ |  |
| `[the] (quit\|leave\|log[ ]out)( \|-)message` | Expression | ExprQuitMessage | ⏳ |  |
| `[the] respawn location` | Expression | ExprRespawnLocation | ⏳ |  |
| `lure enchantment bonus is (applied\|active)` | Condition | CondFishingLure | ⏳ |  |
| `in open water[s]` | Condition | CondIsInOpenWater | ⏳ |  |
| `apply [the] lure enchantment bonus` | Effect | EffFishingLure | ⏳ |  |
| `(reel\|pull) in [the] hook[ed] entity` | Effect | EffPullHookedEntity | ⏳ |  |
| `(min:min[imum]\|max[imum]) fish[ing] approach[ing] angle` | Expression | ExprFishingApproachAngle | ⏳ |  |
| `fish[ing] bit(e\|ing) [wait] time` | Expression | ExprFishingBiteTime | ⏳ |  |
| `fish[ing] (hook\|bobber)` | Expression | ExprFishingHook | ⏳ |  |
| `[the] hook[ed] entity` | Expression | ExprFishingHookEntity | ⏳ |  |
| `(min:min[imum]\|max[imum]) fish[ing] wait[ing] time` | Expression | ExprFishingWaitTime | ⏳ |  |
| `%players% (is\|are) pressing %inputkeys%` | Condition | CondIsPressingKey | ⏳ |  |
| `[current] (inputs\|input keys)` | Expression | ExprCurrentInputKeys | ⏳ |  |
| `[book] (author\|writer\|publisher)` | Expression | ExprBookAuthor | ⏳ |  |
| `[all [[of] the]\|the] [book] (pages\|content) of %itemtypes%` | Expression | ExprBookPages | ⏳ |  |
| `book (name\|title)` | Expression | ExprBookTitle | ⏳ |  |
| `%itemtype% with [a\|the] lore %textcomponents/strings%` | Expression | ExprItemWithLore | ⏳ |  |
| `[the\|a[n]] [item] component copy of %itemcomponents%` | Expression | ExprItemCompCopy | ⏳ |  |
| `%equippablecomponents% will (lose durability\|be damaged) (on [wear...` | Condition | CondEquipCompDamage | ⏳ |  |
| `be dispensed` | Condition | CondEquipCompDispensable | ⏳ |  |
| `be (equipped\|put) on[to] entities` | Condition | CondEquipCompInteract | ⏳ |  |
| `be sheared off [of entities]` | Condition | CondEquipCompShearable | ⏳ |  |
| `swap equipment [on right click\|when right clicked]` | Condition | CondEquipCompSwapEquipment | ⏳ |  |
| `(make\|let) %equippablecomponents% (lose durability\|be damaged) (o...` | Effect | EffEquipCompDamageable | ⏳ |  |
| `allow %equippablecomponents% to be dispensed` | Effect | EffEquipCompDispensable | ⏳ |  |
| `allow %equippablecomponents% to be equipped on[to] entities` | Effect | EffEquipCompInteract | ⏳ |  |
| `allow %equippablecomponents% to be sheared off [of entities]` | Effect | EffEquipCompShearable | ⏳ |  |
| `(allow\|force) %equippablecomponents% to swap equipment [on right c...` | Effect | EffEquipCompSwapEquipment | ⏳ |  |
| `camera overlay` | Expression | ExprEquipCompCameraOverlay | ⏳ |  |
| `allowed entities` | Expression | ExprEquipCompEntities | ⏳ |  |
| `equip sound` | Expression | ExprEquipCompEquipSound | ⏳ |  |
| `equipped (model\|asset) (key\|id)` | Expression | ExprEquipCompModel | ⏳ |  |
| `shear[ed [off]] sound` | Expression | ExprEquipCompShearSound | ⏳ |  |

## Section 12

bukkit.itemcomponents.equippable.elements.expressions, bukkit.loottables.elements.conditions, bukkit.loottables.elements.effects, ...

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `equipment slot` | Expression | ExprEquipCompSlot | ⏳ |  |
| `equippable component[s]` | Expression | ExprEquippableComponent | ⏳ |  |
| `a (blank\|empty) equippable component` | Expression | ExprSecBlankEquipComp | ⏳ |  |
| `[a] loot[ ]table` | Condition | CondHasLootTable | ⏳ |  |
| `lootable` | Condition | CondIsLootable | ⏳ |  |
| `generate [the] loot (of\|using) %loottable% [(with\|using) %-lootco...` | Effect | EffGenerateLoot | ⏳ |  |
| `[the] loot` | Expression | ExprLoot | ⏳ |  |
| `loot[ ]context` | Expression | ExprLootContext | ⏳ |  |
| `looted entity` | Expression | ExprLootContextEntity | ⏳ |  |
| `loot[ing] [context] location` | Expression | ExprLootContextLocation | ⏳ |  |
| `(looter\|looting player)` | Expression | ExprLootContextLooter | ⏳ |  |
| `loot[ing] [context] luck [value\|factor]` | Expression | ExprLootContextLuck | ⏳ |  |
| `[the] loot of %loottables% [(with\|using) [[loot] context] %-lootco...` | Expression | ExprLootItems | ⏳ |  |
| `loot[ ]table[s]` | Expression | ExprLootTable | ⏳ |  |
| `[the] loot[ ]table[s] %strings%` | Expression | ExprLootTableFromString | ⏳ |  |
| `loot[[ ]table] seed[s]` | Expression | ExprLootTableSeed | ⏳ |  |
| `[a] loot context %direction% %location%` | Expression | ExprSecCreateLootContext | ⏳ |  |
| `rotate %vectors/quaternions/displays% around [the] [global] (:x\|:y...` | Effect | EffRotate | ⏳ |  |
| `[the] broadcast(-\|[ed] )message` | Expression | ExprBroadcastMessage | ⏳ |  |
| `colo[u]r[s]` | Expression | ExprColorOf | ⏳ |  |
| `item [inside]` | Expression | ExprItemOfEntity | ⏳ |  |
| `[the] [1:default\|2:shown\|2:displayed] (MOTD\|message of [the] day)` | Expression | ExprMOTD | ⏳ |  |
| `org.joml.Quaternionf` | Expression | ExprQuaternionAxisAngle | ⏳ |  |
| `%quaternions/vectors% rotated around [the] [global] (:x\|:y\|:z)(-\...` | Expression | ExprRotate | ⏳ |  |
| `(skull\|head) texture` | Expression | ExprSkullTexture | ⏳ |  |
| `text[s]` | Expression | ExprTextOf | ⏳ |  |
| `%locations% with [a] (:yaw\|:pitch) [of] %number%` | Expression | ExprWithYawPitch | ⏳ |  |
| `[:force] (play\|show\|draw) %gameeffects/particles% [%-directions% ...` | Effect | EffPlayEffect | ⏳ |  |
| `Could not obtain required data for ` | Expression | ExprGameEffectWithData | ⏳ |  |
| `particle count` | Expression | ExprParticleCount | ⏳ |  |
| `particle distribution` | Expression | ExprParticleDistribution | ⏳ |  |
| `particle offset` | Expression | ExprParticleOffset | ⏳ |  |
| `(particle speed [value]\|extra value)` | Expression | ExprParticleSpeed | ⏳ |  |
| `[%-*number%\|a[n]] ` | Expression | ExprParticleWithData | ⏳ |  |
| `unchecked` | Expression | ExprParticleWithOffset | ⏳ |  |
| `%particles% with ([a] particle speed [value]\|[an] extra value) [of...` | Expression | ExprParticleWithSpeed | ⏳ |  |
| `[persistent] data tag[s] %strings%` | Condition | CondHasPersistentDataTag | ⏳ |  |
| `[all [[of] the]] [persistent] data [tag] keys of %objects%` | Expression | ExprAllPersistentDataKeys | ⏳ |  |
| `[persistent] [%-*classinfo%] [:list] data (value\|tag) %string%` | Expression | ExprPersistentData | ⏳ |  |
| `([any\|a[n]] [active] potion effect[s]\|[any\|a] potion effect[s] a...` | Condition | CondHasPotion | ⏳ |  |
| `poisoned` | Condition | CondIsPoisoned | ⏳ |  |
| `ambient` | Condition | CondIsPotionAmbient | ⏳ |  |
| `instant` | Condition | CondIsPotionInstant | ⏳ |  |
| `([an] icon\|icons)` | Condition | CondPotionHasIcon | ⏳ |  |
| `particles` | Condition | CondPotionHasParticles | ⏳ |  |
| `apply haste 3 to the player for 5 seconds` | Effect | EffApplyPotionEffect | ⏳ |  |
| `poison %livingentities% [for %-timespan%]` | Effect | EffPoison | ⏳ |  |
| `ambient` | Effect | EffPotionAmbient | ⏳ |  |
| `icon[s]` | Effect | EffPotionIcon | ⏳ |  |
| `(infinite\|permanent)` | Effect | EffPotionInfinite | ⏳ |  |
| `particles` | Effect | EffPotionParticles | ⏳ |  |
| `([potion] amplifier\|potion tier\|potion level)[s]` | Expression | ExprPotionAmplifier | ⏳ |  |
| `([potion] duration\|potion length)[s]` | Expression | ExprPotionDuration | ⏳ |  |
| `[:active\|:hidden\|both:(active and hidden\|hidden and active)] %po...` | Expression | ExprPotionEffect | ⏳ |  |
| `[:active\|:hidden\|both:(active and hidden\|hidden and active)] pot...` | Expression | ExprPotionEffects | ⏳ |  |
| `org.bukkit.potion.PotionEffectTypeCategory` | Expression | ExprPotionEffectTypeCategory | ⏳ |  |
| `[a[n]] [:ambient] potion effect of %potioneffecttype% [[of tier] %-...` | Expression | ExprSecPotionEffect | ⏳ |  |
| `[created] [potion] effect` | Expression | ExprSkriptPotionEffect | ⏳ |  |
| `register [a[n]] [custom] ` | Effect | EffRegisterTag | ⏳ |  |
| ` ` | Expression | ExprTag | ⏳ |  |
| `tag (contents\|values)` | Expression | ExprTagContents | ⏳ |  |
| `[namespace[d]] key[s]` | Expression | ExprTagKey | ⏳ |  |
| `[all [[of] the]\|the] ` | Expression | ExprTagsOf | ⏳ |  |
| `[all [[of] the]\|the] ` | Expression | ExprTagsOfType | ⏳ |  |
| `(clear\|delete\|:reset) [the] title[s] [of %audiences%]` | Effect | EffResetTitle | ⏳ |  |
| `%textcomponents% resolved for %commandsender% [bypass:(bypassing\|i...` | Expression | ExprResolvedComponent | ⏳ |  |
| `[all [[of] the]\|the] string colo[u]r[s] [code:code[s]] of %strings%` | Expression | ExprStringColor | ⏳ |  |
| `(expand\|grow) [[the] (diameter\|:radius) of] %worldborders% (by\|:...` | Effect | EffWorldBorderExpand | ⏳ |  |
| `a [virtual] world[ ]border` | Expression | ExprSecCreateWorldBorder | ⏳ |  |
| `world[ ]border` | Expression | ExprWorldBorder | ⏳ |  |
| `world[ ]border (center\|middle)` | Expression | ExprWorldBorderCenter | ⏳ |  |
| `world[ ]border damage amount` | Expression | ExprWorldBorderDamageAmount | ⏳ |  |
| `world[ ]border damage buffer` | Expression | ExprWorldBorderDamageBuffer | ⏳ |  |
| `world[ ]border (size\|diameter\|:radius)` | Expression | ExprWorldBorderSize | ⏳ |  |
| `world[ ]border warning distance` | Expression | ExprWorldBorderWarningDistance | ⏳ |  |
| `world[ ]border warning time` | Expression | ExprWorldBorderWarningTime | ⏳ |  |
| `[the] colo[u]r[s] (from\|of) hex[adecimal] code[s] %strings%` | Expression | ExprColorFromHexCode | ⏳ |  |
| `hex[adecimal] code` | Expression | ExprHexCode | ⏳ |  |
| `[the] recursive (amount\|number\|size) of %objects%` | Expression | ExprRecursiveSize | ⏳ |  |
| `empty` | Condition | PropCondIsEmpty | ⏳ |  |

## Section 13

common.properties.elements.expressions

| Syntax | Kind | Class | Status | Notes |
|---|---|---|---|---|
| `amount[:s]` | Expression | PropExprAmount | ⏳ |  |
| `(display\|nick\|chat\|custom)[ ]name[s]` | Expression | PropExprCustomName | ⏳ |  |
| `name[s]` | Expression | PropExprName | ⏳ |  |
| `number[:s]` | Expression | PropExprNumber | ⏳ |  |
| `progress` | Expression | PropExprProgress | ⏳ |  |
| `scale[s]` | Expression | PropExprScale | ⏳ |  |
| `size[:s]` | Expression | PropExprSize | ⏳ |  |
| `style[s]` | Expression | PropExprStyle | ⏳ |  |
| `title[s]` | Expression | PropExprTitle | ⏳ |  |
| `[%-*classinfo%] value` | Expression | PropExprValueOf | ⏳ |  |
| `viewer[s]` | Expression | PropExprViewers | ⏳ |  |
| `(:x\|:y\|:z\|:w)( \|-)[component[s]\|coord[inate][s]\|dep:(pos[itio...` | Expression | PropExprWXYZ | ⏳ |  |
