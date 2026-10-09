package org.skriptlang.skript.hypertrace;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.EffectSectionEffect;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;

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
 * so the statistics need no synchronization; the hang watchdog only reads {@link #runningLines()}.
 * <p>
 * Every walk of a trigger (see {@link TriggerItem#walk(TriggerItem, Event)}) and every event dispatch is a frame.
 * Each item gets an inclusive time (including functions, events and section bodies it runs in nested frames) and an
 * own time (inclusive minus the time of the items in those nested frames).
 */
@ApiStatus.Internal
public final class Capture {

	static final int MAX_TICKS = 20 * 60 * 60 * 2;
	static final int WORST_TICKS = 10;

	final Thread thread;
	public final boolean lineTiming;
	final long startedMillis = System.currentTimeMillis();
	final long startedNanos = System.nanoTime();
	long stoppedMillis;
	long stoppedNanos;

	final Map<String, LineStats> lines = new HashMap<>();
	private final IdentityHashMap<TriggerItem, LineStats> lineCache = new IdentityHashMap<>();
	final Map<String, TriggerStats> triggers = new HashMap<>();
	private final IdentityHashMap<Trigger, TriggerStats> triggerCache = new IdentityHashMap<>();
	final Map<Class<?>, EventStats> events = new HashMap<>();
	final Map<String, VariableStats> variables = new HashMap<>();
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

	// ticks
	int tickId;
	private boolean inTick;
	/** Skript time since the last tick ended. */
	private long tickSkriptNanos;
	/**
	 * The part of {@link #tickSkriptNanos} outside of the server's tick window (e.g. console commands run between ticks).
	 * The server doesn't count it in its tick time, so it is added to the tick time.
	 */
	private long tickSkriptOutsideNanos;
	volatile long lastProgressNanos = System.nanoTime();
	int ticks;
	float[] tickMs = new float[1200];
	float[] tickSkriptMs = new float[1200];
	long tickSkriptTotalNanos;
	double tickTotalMs;
	int lagTicks;
	private final List<LineStats> touchedLines = new ArrayList<>();
	private final List<TriggerStats> touchedTriggers = new ArrayList<>();
	final List<TickSnapshot> worstTicks = new ArrayList<>();
	final List<TickSnapshot> worstSkriptTicks = new ArrayList<>();
	final List<String> hangs = new ArrayList<>(); // guarded by itself

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
				touchedTriggers.add(stats);
			}
			stats.tickNanos += elapsed;
		}
		if (frame == 1)
			outermost(elapsed);
	}

	public int enterDispatch() {
		return push(null);
	}

	public void exitDispatch(Event event, int frame) {
		long elapsed = pop(frame);
		EventStats stats = events.get(event.getClass());
		if (stats == null)
			events.put(event.getClass(), stats = new EventStats(event.getClass().getSimpleName()));
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
		if (!inTick)
			tickSkriptOutsideNanos += elapsed;
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
		if (elapsed > stats.max)
			stats.max = elapsed;
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
				lines.put(key, stats);
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

	TriggerStats triggerStats(@Nullable Trigger trigger) {
		TriggerStats stats = triggerCache.get(trigger);
		if (stats != null)
			return stats;
		String script = trigger == null ? "(no script)" : scriptName(trigger);
		int line = trigger == null ? -1 : trigger.getLineNumber();
		String name = trigger == null ? "(effect command)" : trigger.getName();
		String key = script + ':' + line + ':' + name;
		stats = triggers.get(key);
		if (stats == null) {
			TriggerKind kind;
			if (trigger == null || trigger.getEvent() instanceof SimpleEvent && !name.startsWith("function ") && !name.startsWith("command "))
				kind = TriggerKind.OTHER;
			else if (name.startsWith("function "))
				kind = TriggerKind.FUNCTION;
			else if (name.startsWith("command "))
				kind = TriggerKind.COMMAND;
			else
				kind = TriggerKind.EVENT;
			triggers.put(key, stats = new TriggerStats(script, line, name, kind));
		}
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
		Path path = script.getConfig().getPath();
		name = script.getConfig().getFileName();
		if (path != null) {
			try {
				Path scripts = Skript.getInstance().getScriptsFolder().toPath().toAbsolutePath();
				Path absolute = path.toAbsolutePath();
				name = (absolute.startsWith(scripts) ? scripts.relativize(absolute) : absolute.getFileName()).toString().replace('\\', '/');
			} catch (RuntimeException ignored) { }
			scriptFiles.put(name, path);
		}
		scriptNames.put(script, name);
		return name;
	}

	// --- events and variables ---

	boolean checkEvent(Trigger trigger, ch.njol.skript.lang.SkriptEvent skriptEvent, Event event) {
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

	void globalWrite(String name, boolean delete) {
		int separator = name.indexOf(ch.njol.skript.lang.Variable.SEPARATOR);
		String group = separator < 0 ? name : name.substring(0, separator) + ch.njol.skript.lang.Variable.SEPARATOR + "*";
		VariableStats stats = variables.get(group);
		if (stats == null)
			variables.put(group, stats = new VariableStats(group));
		if (delete)
			stats.deletes++;
		else
			stats.writes++;
		if (stats.names.size() < VariableStats.MAX_NAMES)
			stats.names.add(name);
		LineStats line = running[depth];
		if (line != null && line != LineStats.TRIGGER) {
			line.globalWrites++;
			stats.writers.merge(line, 1L, Long::sum);
		}
	}

	// --- ticks ---

	void tickStart() {
		inTick = true;
		lastProgressNanos = System.nanoTime();
	}

	void tickEnd(double durationMs) {
		lastProgressNanos = System.nanoTime();
		inTick = false;
		durationMs += tickSkriptOutsideNanos / 1_000_000.0;
		float skriptMs = tickSkriptNanos / 1_000_000f;
		if (ticks < MAX_TICKS) {
			if (ticks == tickMs.length) {
				tickMs = Arrays.copyOf(tickMs, ticks * 2);
				tickSkriptMs = Arrays.copyOf(tickSkriptMs, ticks * 2);
			}
			tickMs[ticks] = (float) durationMs;
			tickSkriptMs[ticks] = skriptMs;
		}
		ticks++;
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
		}
		if (qualifies(worstTicks, (float) durationMs, false) || qualifies(worstSkriptTicks, skriptMs, true)) {
			TickSnapshot snapshot = snapshot((float) durationMs, skriptMs);
			insert(worstTicks, snapshot, false);
			insert(worstSkriptTicks, snapshot, true);
		}
		touchedLines.clear();
		touchedTriggers.clear();
		tickSkriptNanos = 0;
		tickSkriptOutsideNanos = 0;
		tickId++;
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

	private TickSnapshot snapshot(float ms, float skriptMs) {
		TickSnapshot snapshot = new TickSnapshot(ticks, ms, skriptMs,
			(System.currentTimeMillis() - startedMillis) / 1000.0);
		touchedTriggers.stream()
			.sorted(Comparator.comparingLong((TriggerStats t) -> t.tickNanos).reversed())
			.limit(5)
			.forEach(t -> snapshot.triggers.add(new TickSnapshot.Entry(t.label(), t.tickNanos)));
		touchedLines.stream()
			.sorted(Comparator.comparingLong((LineStats l) -> l.tickNanos).reversed())
			.limit(5)
			.forEach(l -> snapshot.lines.add(new TickSnapshot.Entry(l.location() + "  " + l.code, l.tickNanos)));
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
		LineStats[] ancestors = new LineStats[0];
		@Nullable WeakReference<LoopSection> loopRef;

		long calls, total, own, max, block, maxIterations, globalWrites;
		int tickId = -1;
		long tickNanos, maxTick;
		int ticksActive;

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

	}

	static final class TriggerStats {

		final String script;
		final int line;
		final String name;
		final TriggerKind kind;

		long calls, resumes, total, own, max, checks, passes, checkNanos;
		int tickId = -1;
		long tickNanos, maxTick;
		int ticksActive;

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
		long writes, deletes;
		final Set<String> names = new HashSet<>();
		final Map<LineStats, Long> writers = new HashMap<>();

		VariableStats(String group) {
			this.group = group;
		}

		boolean memoryOnly() {
			return group.startsWith(ch.njol.skript.lang.Variable.EPHEMERAL_VARIABLE_TOKEN);
		}

		@Nullable LineStats topWriter() {
			return writers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		}

	}

	static final class TickSnapshot {

		record Entry(String label, long nanos) { }

		final int tick;
		final float ms, skriptMs;
		final double second;
		final List<Entry> triggers = new ArrayList<>();
		final List<Entry> lines = new ArrayList<>();

		TickSnapshot(int tick, float ms, float skriptMs, double second) {
			this.tick = tick;
			this.ms = ms;
			this.skriptMs = skriptMs;
			this.second = second;
		}

	}

}
