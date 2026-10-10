package org.skriptlang.skript.hypertrace;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.lang.EffectSectionEffect;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.SectionSkriptEvent;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.sections.SecWhile;
import ch.njol.skript.variables.Variables;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The data of one HyperTrace capture. Only the thread that started the capture (the server thread) records into it,
 * so the statistics need no synchronization; the hang watchdog only reads {@link #runningLines()} and the variable
 * save thread only adds to {@link #saves}.
 * <p>
 * Every walk of a trigger (see {@link TriggerItem#walk(TriggerItem, Event)}) and every event dispatch is a frame.
 * Each item gets an inclusive time (including functions, events and section bodies it runs in nested frames) and an
 * own time (inclusive minus the time of the items in those nested frames).
 */
@ApiStatus.Internal
public final class Capture {

	static final int MAX_TICKS = 20 * 60 * 60 * 2;
	static final int WORST_TICKS = 10;
	/** The most ints the per-tick trigger activity of one capture may use (3 per trigger per tick it ran in). */
	static final int MAX_ACTIVITY_INTS = 9_000_000;

	/** A line run longer than this is checked for a garbage collection pause. */
	private static final long PAUSE_CHECK_NANOS = 2_000_000;
	/**
	 * The collectors whose time is spent with the server stopped. Collectors that mostly run alongside the server
	 * (the concurrent cycles of G1, ZGC and Shenandoah) are left out.
	 */
	private static final List<GarbageCollectorMXBean> PAUSE_COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans().stream()
		.filter(bean -> !bean.getName().contains("Concurrent") && !bean.getName().contains("Cycles"))
		.toList();

	final Thread thread;
	public final boolean lineTiming;
	/** "capture", "clip" (rolling buffer) or "spike" (automatic clip after a lag spike). */
	String kind = "capture";
	long startedMillis = System.currentTimeMillis();
	long startedNanos = System.nanoTime();
	long stoppedMillis;
	long stoppedNanos;

	final Map<String, LineStats> lines = new HashMap<>();
	private final IdentityHashMap<TriggerItem, LineStats> lineCache = new IdentityHashMap<>();
	final Map<String, TriggerStats> triggers = new HashMap<>();
	private final IdentityHashMap<Trigger, TriggerStats> triggerCache = new IdentityHashMap<>();
	final Map<String, EventStats> events = new HashMap<>();
	final Map<String, VariableStats> variables = new HashMap<>();
	final List<LineStats> loops = new ArrayList<>();
	/** Script display name to its file, for the source view. */
	final Map<String, Path> scriptFiles = new HashMap<>();
	private final IdentityHashMap<Script, String> scriptNames = new IdentityHashMap<>();

	// the running frames: the item each frame is executing (null for event dispatches and between items)
	private LineStats[] running = new LineStats[32];
	private long[] frameStarts = new long[32];
	private long[] frameChildMarks = new long[32];
	private long[] frameItemNanos = new long[32];
	private volatile int depth;
	/**
	 * Time spent in nested frames, used to compute own times: an item's own time is its time minus the growth of this
	 * during the item. A frame sets it to its start value plus its own time when it exits, so frames inside nested
	 * frames aren't counted twice, and HyperTrace's own bookkeeping for nested lines isn't counted as the caller's time.
	 */
	private long child;
	/** Time of frames not spent inside their timed items: HyperTrace's bookkeeping and the walk loop itself. */
	long overheadNanos;

	/** Time spent in Skript on this thread: the sum of all outermost frames. */
	long skriptNanos;
	long itemsTimed;
	final AtomicLong otherThreadRuns = new AtomicLong();

	// variables
	long variableCreates, variableUpdates, variableDeletes;
	/** Variable file saves: {start nanos, end nanos, file size, changes since the last save}. Added by the save thread. */
	final List<long[]> saves = new ArrayList<>(); // guarded by itself

	// garbage collection
	private long tickGcMark = gcPauseMillis();
	private long itemGcMark = tickGcMark;
	long gcPauseMs;
	int gcPauseTicks;

	// ticks
	int tickId;
	/**
	 * Skript time since the last tick ended. Work run between ticks (e.g. console commands) is not always part of the
	 * server's tick time, so a tick's time is at least this.
	 */
	private long tickSkriptNanos;
	private int tickVariableWrites;
	volatile long lastProgressNanos = System.nanoTime();
	int ticks;
	float[] tickMs = new float[1200];
	float[] tickSkriptMs = new float[1200];
	float[] tickGcMs = new float[1200];
	int[] tickVariableWritesArray = new int[1200];
	long[] tickEndNanos = new long[1200];
	long tickSkriptTotalNanos;
	double tickTotalMs;
	int lagTicks;
	int activityInts;
	boolean activityCapped;
	private final List<LineStats> touchedLines = new ArrayList<>();
	private final List<TriggerStats> touchedTriggers = new ArrayList<>();
	final List<TickSnapshot> worstTicks = new ArrayList<>();
	final List<TickSnapshot> worstSkriptTicks = new ArrayList<>();
	final List<String> hangs = new ArrayList<>(); // guarded by itself
	private long lastLoopSampleNanos = System.nanoTime();

	Capture(Thread thread, boolean lineTiming) {
		this.thread = thread;
		this.lineTiming = lineTiming;
	}

	// --- frames ---

	private int push(@Nullable LineStats line) {
		int d = depth + 1;
		if (d >= running.length) {
			running = Arrays.copyOf(running, d * 2);
			frameStarts = Arrays.copyOf(frameStarts, d * 2);
			frameChildMarks = Arrays.copyOf(frameChildMarks, d * 2);
			frameItemNanos = Arrays.copyOf(frameItemNanos, d * 2);
		}
		running[d] = line;
		depth = d;
		frameChildMarks[d] = child;
		frameItemNanos[d] = 0;
		frameStarts[d] = System.nanoTime();
		return d;
	}

	/**
	 * Ends a frame.
	 * @return The frame's time.
	 */
	private long pop(int frame) {
		long elapsed = System.nanoTime() - frameStarts[frame];
		running[frame] = null;
		depth = frame - 1;
		child = frameChildMarks[frame] + elapsed;
		return elapsed;
	}

	/**
	 * Starts a walk frame.
	 * @return The frame, to pass to {@link #exitFrame(TriggerItem, int)}.
	 */
	public int enterFrame() {
		return push(null);
	}

	public void exitFrame(TriggerItem start, int frame) {
		long elapsed = pop(frame);
		if (lineTiming)
			overheadNanos += Math.max(0, elapsed - frameItemNanos[frame]);
		boolean isTrigger = start instanceof Trigger;
		if (isTrigger || frame == 1) {
			Trigger trigger = isTrigger ? (Trigger) start : triggerOf(start);
			TriggerStats stats = triggerStats(trigger);
			if (isTrigger)
				stats.calls++;
			else
				stats.resumes++;
			stats.total += elapsed;
			if (elapsed > stats.max)
				stats.max = elapsed;
			if (stats.tickId != tickId) {
				stats.tickId = tickId;
				stats.tickNanos = 0;
				stats.tickCalls = 0;
				touchedTriggers.add(stats);
			}
			stats.tickNanos += elapsed;
			stats.tickCalls++;
		}
		if (frame == 1)
			outermost(elapsed);
	}

	public int enterDispatch() {
		return push(null);
	}

	public void exitDispatch(Event event, int frame) {
		long elapsed = pop(frame);
		EventStats stats = events.get(event.getEventName());
		if (stats == null)
			events.put(event.getEventName(), stats = new EventStats(event.getEventName()));
		stats.count++;
		stats.total += elapsed;
		if (elapsed > stats.max)
			stats.max = elapsed;
		if (frame == 1)
			outermost(elapsed);
	}

	private void outermost(long elapsed) {
		skriptNanos += elapsed;
		tickSkriptNanos += elapsed;
	}

	// --- items ---

	/**
	 * Called before an item runs.
	 * @return The child time mark to pass to {@link #endItem}.
	 */
	public long beginItem(TriggerItem item) {
		LineStats stats = lineCache.get(item);
		if (stats == null)
			stats = lineStats(item);
		running[depth] = stats;
		return child;
	}

	public void endItem(TriggerItem item, Event event, long started, long childMark) {
		long elapsed = System.nanoTime() - started;
		int d = depth;
		LineStats stats = running[d];
		running[d] = null;
		long own = elapsed - (child - childMark);
		frameItemNanos[d] += elapsed;
		if (stats == null || stats == LineStats.TRIGGER)
			return;
		if (own < 0)
			own = 0;
		itemsTimed++;
		stats.calls++;
		stats.total += elapsed;
		stats.own += own;
		if (elapsed > stats.max) {
			stats.max = elapsed;
			stats.maxPause = elapsed >= PAUSE_CHECK_NANOS ? pauseDuring(elapsed) : 0;
		}
		if (stats.tickId != tickId) {
			stats.tickId = tickId;
			stats.tickNanos = 0;
			touchedLines.add(stats);
		}
		stats.tickNanos += own;
		if (stats.trigger != null)
			stats.trigger.own += own;
		if (stats.section)
			stats.block += elapsed;
		if (stats.loop) {
			long counter = ((LoopSection) item).getLoopCounter(event);
			if (counter > stats.maxIterations)
				stats.maxIterations = counter;
		}
		// sections around this item get its time, unless they are running it in a nested frame (then it's in their own time already)
		for (LineStats ancestor : stats.ancestors) {
			if (isRunning(ancestor, d))
				break;
			ancestor.block += elapsed;
		}
	}

	/**
	 * @return The garbage collection pause time (in nanoseconds) since the last check, if it fits into an item that
	 * just took {@code elapsed} nanoseconds; otherwise 0. A pause ends up in whatever was running when it happened.
	 */
	private long pauseDuring(long elapsed) {
		long gc = gcPauseMillis();
		long pause = (gc - itemGcMark) * 1_000_000;
		itemGcMark = gc;
		return pause > 0 && pause <= elapsed + 1_000_000 ? Math.min(pause, elapsed) : 0;
	}

	/**
	 * @return The total time all stop-the-world garbage collectors have run, in milliseconds.
	 */
	static long gcPauseMillis() {
		long total = 0;
		for (GarbageCollectorMXBean bean : PAUSE_COLLECTORS) {
			long time = bean.getCollectionTime();
			if (time > 0)
				total += time;
		}
		return total;
	}

	private boolean isRunning(LineStats stats, int belowDepth) {
		for (int i = 1; i < belowDepth; i++) {
			if (running[i] == stats)
				return true;
		}
		return false;
	}

	/**
	 * @return The lines currently running, outermost first. May be read from other threads (racy, for diagnostics).
	 */
	List<LineStats> runningLines() {
		List<LineStats> result = new ArrayList<>();
		LineStats[] frames = running;
		int d = Math.min(depth, frames.length - 1);
		for (int i = 1; i <= d; i++) {
			LineStats stats = frames[i];
			if (stats != null && stats != LineStats.TRIGGER)
				result.add(stats);
		}
		return result;
	}

	private LineStats lineStats(TriggerItem item) {
		LineStats stats;
		if (item instanceof Trigger) {
			stats = LineStats.TRIGGER;
		} else {
			TriggerItem structural = structural(item);
			Trigger trigger = structural.getTrigger();
			String script = scriptName(trigger);
			int line = item.getHyperTraceLine();
			String code = describe(item);
			String key = line > 0 ? script + ':' + line : script + ":?:" + code;
			stats = lines.get(key);
			if (stats == null) {
				stats = new LineStats(script, line, code, trigger == null ? null : triggerStats(trigger),
					item instanceof TriggerSection, item instanceof LoopSection);
				stats.isWhile = item instanceof SecWhile;
				lines.put(key, stats);
				if (stats.loop)
					loops.add(stats);
				List<LineStats> ancestors = new ArrayList<>();
				for (TriggerSection parent = structural.getParent(); parent != null && !(parent instanceof Trigger); parent = parent.getParent())
					ancestors.add(lineStats(parent));
				stats.ancestors = ancestors.toArray(new LineStats[0]);
			}
			if (item instanceof LoopSection loop)
				stats.loopRef = new WeakReference<>(loop);
		}
		lineCache.put(item, stats);
		return stats;
	}

	/**
	 * An effect section used as an effect is wrapped; the wrapped section holds the parent.
	 */
	private static TriggerItem structural(TriggerItem item) {
		return item instanceof EffectSectionEffect wrapper ? wrapper.getEffectSection() : item;
	}

	private static @Nullable Trigger triggerOf(TriggerItem item) {
		return structural(item).getTrigger();
	}

	private static String describe(TriggerItem item) {
		try {
			return item.toString(null, false);
		} catch (RuntimeException e) {
			return item.getClass().getSimpleName();
		}
	}

	static TriggerKind kindOf(@Nullable Trigger trigger) {
		if (trigger == null)
			return TriggerKind.OTHER;
		String name = trigger.getName();
		if (name.startsWith("function "))
			return TriggerKind.FUNCTION;
		if (name.startsWith("command "))
			return TriggerKind.COMMAND;
		SkriptEvent event = trigger.getEvent();
		if (event instanceof SimpleEvent || event instanceof SectionSkriptEvent)
			return TriggerKind.OTHER;
		return TriggerKind.EVENT;
	}

	/**
	 * @return The line a trigger starts at, or the line of its first statement if it has none (e.g. triggers that
	 * sections create), or -1.
	 */
	static int lineOf(@Nullable Trigger trigger) {
		if (trigger == null)
			return -1;
		if (trigger.getLineNumber() > 0)
			return trigger.getLineNumber();
		TriggerItem first = trigger.getHyperTraceFirstItem();
		return first == null ? -1 : first.getHyperTraceLine();
	}

	static String triggerKey(String script, int line, String name) {
		return script + ':' + line + ':' + name;
	}

	TriggerStats triggerStats(@Nullable Trigger trigger) {
		TriggerStats stats = triggerCache.get(trigger);
		if (stats != null)
			return stats;
		String script = trigger == null ? "(no script)" : scriptName(trigger);
		int line = lineOf(trigger);
		String name = trigger == null ? "(effect command)" : trigger.getName();
		String key = triggerKey(script, line, name);
		stats = triggers.get(key);
		if (stats == null)
			triggers.put(key, stats = new TriggerStats(script, line, name, kindOf(trigger)));
		triggerCache.put(trigger, stats);
		return stats;
	}

	private String scriptName(@Nullable Trigger trigger) {
		Script script = trigger == null ? null : trigger.getScript();
		if (script == null)
			return "(no script)";
		String name = scriptNames.get(script);
		if (name != null)
			return name;
		name = displayName(script);
		Path path = script.getConfig().getPath();
		if (path != null)
			scriptFiles.put(name, path);
		scriptNames.put(script, name);
		return name;
	}

	/**
	 * @return The path of a script relative to the scripts folder, with forward slashes.
	 */
	static String displayName(Script script) {
		Path path = script.getConfig().getPath();
		String name = script.getConfig().getFileName();
		if (path != null) {
			try {
				Path scripts = Skript.getInstance().getScriptsFolder().toPath().toAbsolutePath();
				Path absolute = path.toAbsolutePath();
				name = (absolute.startsWith(scripts) ? scripts.relativize(absolute) : absolute.getFileName()).toString().replace('\\', '/');
			} catch (RuntimeException ignored) { }
		}
		return name;
	}

	// --- events and variables ---

	boolean checkEvent(Trigger trigger, SkriptEvent skriptEvent, Event event) {
		long started = System.nanoTime();
		boolean passed = skriptEvent.check(event);
		long elapsed = System.nanoTime() - started;
		TriggerStats stats = triggerStats(trigger);
		stats.checks++;
		stats.checkNanos += elapsed;
		if (passed)
			stats.passes++;
		return passed;
	}

	void globalWrite(String name, @Nullable Object value) {
		int separator = name.indexOf(Variable.SEPARATOR);
		String group = separator < 0 ? name : name.substring(0, separator) + Variable.SEPARATOR + "*";
		VariableStats stats = variables.get(group);
		if (stats == null)
			variables.put(group, stats = new VariableStats(group));
		if (value == null) {
			stats.deletes++;
			variableDeletes++;
		} else {
			if (Variables.getVariable(name, null, false) == null) {
				stats.creates++;
				variableCreates++;
			} else {
				stats.updates++;
				variableUpdates++;
			}
			ClassInfo<?> type = Classes.getSuperClassInfo(value.getClass());
			stats.lastType = type.getCodeName();
		}
		tickVariableWrites++;
		if (stats.names.size() < VariableStats.MAX_NAMES)
			stats.names.add(name);
		LineStats line = running[depth];
		if (line != null && line != LineStats.TRIGGER) {
			line.globalWrites++;
			stats.writers.merge(line, 1L, Long::sum);
		}
	}

	/**
	 * Records a full save of the variables file. Called from the save thread.
	 */
	void variablesSaved(long startNanos, long endNanos, long bytes, int changes) {
		synchronized (saves) {
			saves.add(new long[] {startNanos, endNanos, bytes, changes});
		}
	}

	// --- ticks ---

	void tickStart() {
		lastProgressNanos = System.nanoTime();
	}

	/**
	 * @return The tick's time: the server's, or Skript's time since the last tick if that is longer.
	 */
	double tickEnd(double durationMs) {
		long now = System.nanoTime();
		lastProgressNanos = now;
		durationMs = Math.max(durationMs, tickSkriptNanos / 1_000_000.0);
		float skriptMs = tickSkriptNanos / 1_000_000f;
		long gc = gcPauseMillis();
		long gcMs = Math.max(0, gc - tickGcMark);
		tickGcMark = gc;
		itemGcMark = gc;
		if (gcMs > 0) {
			gcPauseMs += gcMs;
			gcPauseTicks++;
		}
		if (ticks < MAX_TICKS) {
			if (ticks == tickMs.length) {
				int size = Math.min(MAX_TICKS, ticks * 2);
				tickMs = Arrays.copyOf(tickMs, size);
				tickSkriptMs = Arrays.copyOf(tickSkriptMs, size);
				tickGcMs = Arrays.copyOf(tickGcMs, size);
				tickVariableWritesArray = Arrays.copyOf(tickVariableWritesArray, size);
				tickEndNanos = Arrays.copyOf(tickEndNanos, size);
			}
			tickMs[ticks] = (float) durationMs;
			tickSkriptMs[ticks] = skriptMs;
			tickGcMs[ticks] = gcMs;
			tickVariableWritesArray[ticks] = tickVariableWrites;
			tickEndNanos[ticks] = now;
		}
		tickTotalMs += durationMs;
		tickSkriptTotalNanos += tickSkriptNanos;
		if (durationMs > 50)
			lagTicks++;

		for (LineStats line : touchedLines) {
			line.ticksActive++;
			if (line.tickNanos > line.maxTick)
				line.maxTick = line.tickNanos;
		}
		for (TriggerStats trigger : touchedTriggers) {
			trigger.ticksActive++;
			if (trigger.tickNanos > trigger.maxTick)
				trigger.maxTick = trigger.tickNanos;
			if (ticks < MAX_TICKS && !activityCapped) {
				if (activityInts + 3 > MAX_ACTIVITY_INTS) {
					activityCapped = true;
				} else {
					trigger.addActivity(ticks, trigger.tickNanos, trigger.tickCalls);
					activityInts += 3;
				}
			}
		}
		ticks++;
		if (qualifies(worstTicks, (float) durationMs, false) || qualifies(worstSkriptTicks, skriptMs, true)) {
			TickSnapshot snapshot = snapshot((float) durationMs, skriptMs, gcMs);
			insert(worstTicks, snapshot, false);
			insert(worstSkriptTicks, snapshot, true);
		}
		touchedLines.clear();
		touchedTriggers.clear();
		tickSkriptNanos = 0;
		tickVariableWrites = 0;
		tickId++;
		if (now - lastLoopSampleNanos >= 1_000_000_000L)
			sampleLoops(now);
		return durationMs;
	}

	/**
	 * Samples the running loops once per second: how many run at once, their iteration and how fast they climb.
	 */
	private void sampleLoops(long now) {
		double seconds = (now - lastLoopSampleNanos) / 1e9;
		lastLoopSampleNanos = now;
		for (LineStats loop : loops) {
			LoopSection section = loop.loopRef == null ? null : loop.loopRef.get();
			List<Long> counters = section == null ? List.of() : section.getRunningLoopCounters();
			loop.runningNow = counters.size();
			loop.currentIteration = 0;
			for (Long counter : counters) {
				if (counter != null && counter > loop.currentIteration)
					loop.currentIteration = counter;
			}
			if (counters.size() > loop.peakConcurrent)
				loop.peakConcurrent = counters.size();
			loop.iterationsPerSecond = seconds <= 0 ? 0 : (loop.calls - loop.lastSampleCalls) / seconds;
			loop.lastSampleCalls = loop.calls;
		}
	}

	private static boolean qualifies(List<TickSnapshot> list, float value, boolean skript) {
		return value > 0 && (list.size() < WORST_TICKS || value > key(list.getLast(), skript));
	}

	private static float key(TickSnapshot snapshot, boolean skript) {
		return skript ? snapshot.skriptMs : snapshot.ms;
	}

	private static void insert(List<TickSnapshot> list, TickSnapshot snapshot, boolean skript) {
		if (!qualifies(list, key(snapshot, skript), skript))
			return;
		list.add(snapshot);
		list.sort(Comparator.comparingDouble((TickSnapshot s) -> key(s, skript)).reversed());
		if (list.size() > WORST_TICKS)
			list.removeLast();
	}

	private TickSnapshot snapshot(float ms, float skriptMs, long gcMs) {
		TickSnapshot snapshot = new TickSnapshot(ticks, ms, skriptMs, gcMs,
			(System.currentTimeMillis() - startedMillis) / 1000.0);
		touchedTriggers.stream()
			.sorted(Comparator.comparingLong((TriggerStats t) -> t.tickNanos).reversed())
			.limit(5)
			.forEach(t -> snapshot.triggers.add(new TickSnapshot.Entry(t.label(), t.script, t.line, t.tickNanos)));
		touchedLines.stream()
			.sorted(Comparator.comparingLong((LineStats l) -> l.tickNanos).reversed())
			.limit(5)
			.forEach(l -> snapshot.lines.add(new TickSnapshot.Entry(l.code, l.script, l.line, l.tickNanos)));
		return snapshot;
	}

	// --- derived values ---

	long elapsedNanos() {
		return (stoppedNanos != 0 ? stoppedNanos : System.nanoTime()) - startedNanos;
	}

	boolean isRunning() {
		return stoppedNanos == 0;
	}

	/** Divides a time by the number of ticks measured, in milliseconds. */
	double perTickMs(long nanos) {
		return ticks == 0 ? 0 : nanos / 1_000_000.0 / ticks;
	}

	double share(long nanos) {
		return skriptNanos == 0 ? 0 : 100.0 * nanos / skriptNanos;
	}

	/**
	 * @return The index of the tick that ended last before the given time.
	 */
	int tickAt(long nanos) {
		int count = Math.min(ticks, MAX_TICKS);
		int index = Arrays.binarySearch(tickEndNanos, 0, count, nanos);
		if (index < 0)
			index = -index - 1;
		return Math.max(0, Math.min(count - 1, index));
	}

	// --- merging (rolling buffer clips) ---

	/**
	 * Combines consecutive captures (segments of the rolling buffer) into one, for a report.
	 */
	static Capture merge(List<Capture> parts, String kind) {
		Capture first = parts.getFirst();
		Capture merged = new Capture(first.thread, first.lineTiming);
		merged.kind = kind;
		merged.startedMillis = first.startedMillis;
		merged.startedNanos = first.startedNanos;
		Capture last = parts.getLast();
		merged.stoppedNanos = last.stoppedNanos != 0 ? last.stoppedNanos : System.nanoTime();
		merged.stoppedMillis = last.stoppedMillis != 0 ? last.stoppedMillis : System.currentTimeMillis();
		Map<TriggerStats, TriggerStats> triggerMap = new IdentityHashMap<>();
		Map<LineStats, LineStats> lineMap = new IdentityHashMap<>();
		for (Capture part : parts) {
			int tickOffset = merged.ticks;
			double secondOffset = (part.startedMillis - first.startedMillis) / 1000.0;
			for (Map.Entry<String, TriggerStats> entry : part.triggers.entrySet()) {
				TriggerStats source = entry.getValue();
				TriggerStats target = merged.triggers.computeIfAbsent(entry.getKey(),
					k -> new TriggerStats(source.script, source.line, source.name, source.kind));
				target.add(source, tickOffset);
				triggerMap.put(source, target);
			}
			for (Map.Entry<String, LineStats> entry : part.lines.entrySet()) {
				LineStats source = entry.getValue();
				LineStats target = merged.lines.get(entry.getKey());
				if (target == null) {
					target = new LineStats(source.script, source.line, source.code,
						source.trigger == null ? null : triggerMap.get(source.trigger), source.section, source.loop);
					target.isWhile = source.isWhile;
					merged.lines.put(entry.getKey(), target);
					if (target.loop)
						merged.loops.add(target);
				}
				target.add(source);
				lineMap.put(source, target);
			}
			for (Map.Entry<String, EventStats> entry : part.events.entrySet()) {
				EventStats target = merged.events.computeIfAbsent(entry.getKey(), EventStats::new);
				EventStats source = entry.getValue();
				target.count += source.count;
				target.total += source.total;
				target.max = Math.max(target.max, source.max);
			}
			for (Map.Entry<String, VariableStats> entry : part.variables.entrySet()) {
				VariableStats target = merged.variables.computeIfAbsent(entry.getKey(), VariableStats::new);
				VariableStats source = entry.getValue();
				target.creates += source.creates;
				target.updates += source.updates;
				target.deletes += source.deletes;
				if (source.lastType != null)
					target.lastType = source.lastType;
				for (String name : source.names) {
					if (target.names.size() >= VariableStats.MAX_NAMES)
						break;
					target.names.add(name);
				}
				source.writers.forEach((line, count) -> {
					LineStats mapped = lineMap.get(line);
					if (mapped != null)
						target.writers.merge(mapped, count, Long::sum);
				});
			}
			merged.scriptFiles.putAll(part.scriptFiles);
			merged.skriptNanos += part.skriptNanos;
			merged.itemsTimed += part.itemsTimed;
			merged.overheadNanos += part.overheadNanos;
			merged.otherThreadRuns.addAndGet(part.otherThreadRuns.get());
			merged.variableCreates += part.variableCreates;
			merged.variableUpdates += part.variableUpdates;
			merged.variableDeletes += part.variableDeletes;
			merged.tickSkriptTotalNanos += part.tickSkriptTotalNanos;
			merged.tickTotalMs += part.tickTotalMs;
			merged.lagTicks += part.lagTicks;
			merged.gcPauseMs += part.gcPauseMs;
			merged.gcPauseTicks += part.gcPauseTicks;
			merged.activityCapped |= part.activityCapped;
			int count = Math.min(part.ticks, MAX_TICKS);
			for (int i = 0; i < count && merged.ticks < MAX_TICKS; i++) {
				merged.appendTick(part.tickMs[i], part.tickSkriptMs[i], part.tickGcMs[i], part.tickVariableWritesArray[i], part.tickEndNanos[i]);
			}
			for (TickSnapshot snapshot : part.worstTicks)
				insert(merged.worstTicks, snapshot.shifted(tickOffset, secondOffset), false);
			for (TickSnapshot snapshot : part.worstSkriptTicks)
				insert(merged.worstSkriptTicks, snapshot.shifted(tickOffset, secondOffset), true);
			synchronized (part.hangs) {
				merged.hangs.addAll(part.hangs);
			}
			synchronized (part.saves) {
				merged.saves.addAll(part.saves);
			}
		}
		return merged;
	}

	private void appendTick(float ms, float skriptMs, float gcMs, int variableWrites, long endNanos) {
		if (ticks == tickMs.length) {
			int size = Math.min(MAX_TICKS, ticks * 2);
			tickMs = Arrays.copyOf(tickMs, size);
			tickSkriptMs = Arrays.copyOf(tickSkriptMs, size);
			tickGcMs = Arrays.copyOf(tickGcMs, size);
			tickVariableWritesArray = Arrays.copyOf(tickVariableWritesArray, size);
			tickEndNanos = Arrays.copyOf(tickEndNanos, size);
		}
		tickMs[ticks] = ms;
		tickSkriptMs[ticks] = skriptMs;
		tickGcMs[ticks] = gcMs;
		tickVariableWritesArray[ticks] = variableWrites;
		tickEndNanos[ticks] = endNanos;
		ticks++;
	}

	// --- statistics ---

	enum TriggerKind { EVENT, COMMAND, FUNCTION, OTHER }

	static final class LineStats {

		static final LineStats TRIGGER = new LineStats("", -1, "", null, false, false);

		final String script;
		final int line;
		final String code;
		final @Nullable TriggerStats trigger;
		final boolean section;
		final boolean loop;
		boolean isWhile;
		LineStats[] ancestors = new LineStats[0];
		@Nullable WeakReference<LoopSection> loopRef;

		long calls, total, own, max, block, maxIterations, globalWrites;
		/** Garbage collection pause time inside the slowest run. */
		long maxPause;
		int tickId = -1;
		long tickNanos, maxTick;
		int ticksActive;
		// loop samples
		int runningNow, peakConcurrent;
		long currentIteration, lastSampleCalls;
		double iterationsPerSecond;

		LineStats(String script, int line, String code, @Nullable TriggerStats trigger, boolean section, boolean loop) {
			this.script = script;
			this.line = line;
			this.code = code;
			this.trigger = trigger;
			this.section = section;
			this.loop = loop;
		}

		String location() {
			return script + ':' + (line > 0 ? String.valueOf(line) : "?");
		}

		void add(LineStats other) {
			calls += other.calls;
			total += other.total;
			own += other.own;
			if (other.max > max) {
				max = other.max;
				maxPause = other.maxPause;
			}
			block += other.block;
			maxIterations = Math.max(maxIterations, other.maxIterations);
			globalWrites += other.globalWrites;
			maxTick = Math.max(maxTick, other.maxTick);
			ticksActive += other.ticksActive;
			runningNow = other.runningNow;
			currentIteration = other.currentIteration;
			iterationsPerSecond = other.iterationsPerSecond;
			peakConcurrent = Math.max(peakConcurrent, other.peakConcurrent);
			if (other.loopRef != null)
				loopRef = other.loopRef;
		}

	}

	static final class TriggerStats {

		final String script;
		final int line;
		final String name;
		final TriggerKind kind;

		long calls, resumes, total, own, max, checks, passes, checkNanos;
		int tickId = -1;
		long tickNanos, maxTick;
		int tickCalls;
		int ticksActive;
		/** Per tick it ran in: tick index, time in units of 100 ns, runs. */
		int[] activity = new int[0];
		int activitySize;

		TriggerStats(String script, int line, String name, TriggerKind kind) {
			this.script = script;
			this.line = line;
			this.name = name;
			this.kind = kind;
		}

		String location() {
			return script + ':' + (line > 0 ? String.valueOf(line) : "?");
		}

		String label() {
			return name + " (" + location() + ")";
		}

		/** Time including the event filter checks. */
		long totalWithChecks() {
			return total + checkNanos;
		}

		void addActivity(int tick, long nanos, int runs) {
			if (activitySize + 3 > activity.length)
				activity = Arrays.copyOf(activity, Math.max(48, activity.length * 2));
			activity[activitySize++] = tick;
			activity[activitySize++] = (int) Math.min(Integer.MAX_VALUE, nanos / 100);
			activity[activitySize++] = runs;
		}

		void add(TriggerStats other, int tickOffset) {
			calls += other.calls;
			resumes += other.resumes;
			total += other.total;
			own += other.own;
			max = Math.max(max, other.max);
			checks += other.checks;
			passes += other.passes;
			checkNanos += other.checkNanos;
			maxTick = Math.max(maxTick, other.maxTick);
			ticksActive += other.ticksActive;
			for (int i = 0; i + 2 < other.activitySize; i += 3) {
				if (activitySize + 3 > activity.length)
					activity = Arrays.copyOf(activity, Math.max(48, activity.length * 2));
				activity[activitySize++] = other.activity[i] + tickOffset;
				activity[activitySize++] = other.activity[i + 1];
				activity[activitySize++] = other.activity[i + 2];
			}
		}

	}

	static final class EventStats {

		final String name;
		long count, total, max;

		EventStats(String name) {
			this.name = name;
		}

	}

	static final class VariableStats {

		static final int MAX_NAMES = 10_000;

		final String group;
		long creates, updates, deletes;
		@Nullable String lastType;
		final Set<String> names = new HashSet<>();
		final Map<LineStats, Long> writers = new HashMap<>();

		VariableStats(String group) {
			this.group = group;
		}

		long writes() {
			return creates + updates + deletes;
		}

		boolean memoryOnly() {
			return group.startsWith(Variable.EPHEMERAL_VARIABLE_TOKEN);
		}

		@Nullable LineStats topWriter() {
			return writers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		}

	}

	static final class TickSnapshot {

		record Entry(String label, String script, int line, long nanos) { }

		final int tick;
		final float ms, skriptMs;
		/** Garbage collection pause time in the tick, in milliseconds. */
		final long gcMs;
		final double second;
		final List<Entry> triggers = new ArrayList<>();
		final List<Entry> lines = new ArrayList<>();

		TickSnapshot(int tick, float ms, float skriptMs, long gcMs, double second) {
			this.tick = tick;
			this.ms = ms;
			this.skriptMs = skriptMs;
			this.gcMs = gcMs;
			this.second = second;
		}

		TickSnapshot shifted(int tickOffset, double secondOffset) {
			TickSnapshot copy = new TickSnapshot(tick + tickOffset, ms, skriptMs, gcMs, second + secondOffset);
			copy.triggers.addAll(triggers);
			copy.lines.addAll(lines);
			return copy;
		}

	}

}
