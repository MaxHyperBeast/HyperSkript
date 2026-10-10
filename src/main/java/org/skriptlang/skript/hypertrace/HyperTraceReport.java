package org.skriptlang.skript.hypertrace;

import ch.njol.skript.Skript;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.hypertrace.Capture.EventStats;
import org.skriptlang.skript.hypertrace.Capture.LineStats;
import org.skriptlang.skript.hypertrace.Capture.TickSnapshot;
import org.skriptlang.skript.hypertrace.Capture.TriggerKind;
import org.skriptlang.skript.hypertrace.Capture.TriggerStats;
import org.skriptlang.skript.hypertrace.Capture.VariableStats;
import org.skriptlang.skript.hypertrace.HyperTrace.SilentTrigger;
import org.skriptlang.skript.hypertrace.HyperTrace.SilentTriggers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the findings of a capture and its report data (JSON, which the report page renders).
 * Must be used on the thread that owns the capture.
 */
final class HyperTraceReport {

	private static final long MAX_SOURCE_BYTES = 2_000_000;

	private final Capture data;
	private final SilentTriggers silent;
	private final boolean includeSources;

	HyperTraceReport(Capture data, SilentTriggers silent, boolean includeSources) {
		this.data = data;
		this.silent = silent;
		this.includeSources = includeSources;
	}

	// --- shared with the command ---

	static List<LineStats> sortedLines(Capture data) {
		List<LineStats> sorted = new ArrayList<>(data.lines.values());
		sorted.removeIf(line -> line.calls == 0);
		sorted.sort(Comparator.comparingLong((LineStats line) -> line.own).reversed());
		return sorted;
	}

	static List<LineStats> sortedBlocks(Capture data) {
		List<LineStats> sorted = new ArrayList<>();
		for (LineStats line : data.lines.values()) {
			if (line.section && line.block > 0)
				sorted.add(line);
		}
		sorted.sort(Comparator.comparingLong((LineStats line) -> line.block).reversed());
		return sorted;
	}

	static List<TriggerStats> sortedTriggers(Capture data, boolean functions) {
		List<TriggerStats> sorted = new ArrayList<>();
		for (TriggerStats trigger : data.triggers.values()) {
			if ((trigger.kind == TriggerKind.FUNCTION) == functions && (trigger.calls + trigger.resumes + trigger.checks) > 0)
				sorted.add(trigger);
		}
		sorted.sort(Comparator.comparingLong(TriggerStats::totalWithChecks).reversed());
		return sorted;
	}

	static List<VariableStats> sortedVariables(Capture data) {
		List<VariableStats> sorted = new ArrayList<>(data.variables.values());
		sorted.sort(Comparator.comparingLong(VariableStats::writes).reversed());
		return sorted;
	}

	/**
	 * @return Why a line is expensive, in a few words, or null if nothing stands out.
	 */
	static @Nullable String diagnose(Capture data, LineStats line) {
		if (line.calls == 0)
			return null;
		double callsPerTick = data.ticks == 0 ? 0 : (double) line.calls / data.ticks;
		double averageNanos = (double) line.total / line.calls;
		double typicalTickNanos = line.ticksActive == 0 ? 0 : (double) line.own / line.ticksActive;
		List<String> reasons = new ArrayList<>();
		if (callsPerTick >= 50)
			reasons.add("runs very often (" + number(callsPerTick) + "× per tick)");
		if (averageNanos >= 500_000)
			reasons.add("is slow every time it runs (" + micros(averageNanos) + " each)");
		// a line that is normally fast but had one very slow run was usually caught by a server-wide pause
		boolean oneOff = line.max >= 5_000_000 && line.calls >= 20 && line.max > 50 * averageNanos;
		if (oneOff) {
			if (line.maxPause * 2 >= line.max) {
				reasons.add("had one slow run (" + ms(line.max) + ", of which " + ms(line.maxPause)
					+ " was a garbage collection pause of the whole server); it normally takes " + micros(averageNanos));
			} else {
				reasons.add("had one unusually slow run (" + ms(line.max) + "; it normally takes " + micros(averageNanos)
					+ "), probably a pause of the whole server rather than the line itself");
			}
		} else if (line.maxTick >= 5_000_000 && line.maxTick > 10 * typicalTickNanos) {
			reasons.add("causes lag spikes (up to " + ms(line.maxTick) + " in one tick)");
		}
		if (line.loop && line.maxIterations >= 100)
			reasons.add("loops up to " + String.format(Locale.ROOT, "%,d", line.maxIterations) + " times per run");
		if (line.total > 2 * line.own && line.total - line.own > 1_000_000)
			reasons.add("spends most of its time in what it calls (functions, events or section bodies)");
		return reasons.isEmpty() ? null : String.join("; ", reasons);
	}

	/**
	 * @return The main findings of a capture, as plain sentences (most important first).
	 */
	static List<String> findings(Capture data, @Nullable SilentTriggers silent) {
		List<String> findings = new ArrayList<>();
		if (data.ticks > 0) {
			double avgTick = data.tickTotalMs / data.ticks;
			double skriptPerTick = data.tickSkriptTotalNanos / 1_000_000.0 / data.ticks;
			findings.add(String.format(Locale.ROOT, "Skript used %.2f ms per tick on average, %.0f%% of the average tick (%.2f ms; 50 ms is the limit before the server lags).",
				skriptPerTick, avgTick == 0 ? 0 : 100 * skriptPerTick / avgTick, avgTick));
		}
		List<LineStats> lines = sortedLines(data);
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			LineStats line = lines.get(i);
			if (data.share(line.own) < 5 && i > 0)
				break;
			String reason = diagnose(data, line);
			findings.add(String.format(Locale.ROOT, "%s line %s (%s): %.0f%% of all Skript time, %s per tick%s.",
				i == 0 ? "The most expensive" : i == 1 ? "Second most expensive" : "Third most expensive",
				line.location(), shorten(line.code, 80), data.share(line.own), ms(perTick(data, line.own)),
				reason == null ? "" : "; it " + reason));
		}
		List<LineStats> blocks = sortedBlocks(data);
		if (!blocks.isEmpty() && data.share(blocks.getFirst().block) >= 25) {
			LineStats block = blocks.getFirst();
			findings.add(String.format(Locale.ROOT, "The %s at %s (%s) takes %.0f%% of all Skript time including the lines inside it (%s per tick)%s.",
				block.loop ? "loop" : "section", block.location(), shorten(block.code, 80), data.share(block.block),
				ms(perTick(data, block.block)),
				block.loop && block.maxIterations > 1 ? ", up to " + String.format(Locale.ROOT, "%,d", block.maxIterations) + " iterations per run" : ""));
		}
		for (TriggerStats trigger : sortedTriggers(data, false)) {
			if (trigger.checks >= 1000 && trigger.checkNanos > 1_000_000 && data.share(trigger.checkNanos) >= 5) {
				findings.add(String.format(Locale.ROOT, "\"%s\" (%s) was checked %,d times but ran only %,d times; its event filter alone used %.0f%% of Skript time. A more specific event or an early stop helps.",
					trigger.name, trigger.location(), trigger.checks, trigger.passes, data.share(trigger.checkNanos)));
				break;
			}
		}
		if (!data.worstTicks.isEmpty()) {
			TickSnapshot worst = data.worstTicks.getFirst();
			String cause;
			if (worst.gcMs >= worst.ms * 0.5) {
				cause = "; most of the tick was a garbage collection pause (" + worst.gcMs + " ms), which stops the whole server";
			} else if (worst.skriptMs >= worst.ms * 0.5) {
				cause = worst.triggers.isEmpty() ? "" : ", mostly in " + worst.triggers.getFirst().label() + " (" + ms(worst.triggers.getFirst().nanos()) + ")";
			} else {
				cause = "; Skript was not the main cause of that tick" + (worst.gcMs > 0 ? " (it included a " + worst.gcMs + " ms garbage collection pause)" : "");
			}
			findings.add(String.format(Locale.ROOT, "The slowest tick took %.1f ms (%.0f s into the capture). Skript used %.1f ms of it%s.",
				worst.ms, worst.second, worst.skriptMs, cause));
		}
		if (data.gcPauseMs > 0 && data.ticks > 0) {
			long gcTicks = data.worstTicks.stream().filter(tick -> tick.gcMs >= tick.ms * 0.5).count();
			double share = data.tickTotalMs <= 0 ? 0 : 100.0 * data.gcPauseMs / data.tickTotalMs;
			if (gcTicks > 0 || share >= 5) {
				findings.add(String.format(Locale.ROOT, "Garbage collection paused the server for %,d ms in total (%.0f%% of all tick time, in %,d ticks)%s. Those pauses stop every plugin, not just Skript; fewer and smaller objects, or a larger heap, make them shorter.",
					data.gcPauseMs, share, data.gcPauseTicks,
					gcTicks > 0 ? "; " + gcTicks + " of the " + data.worstTicks.size() + " slowest ticks were mostly a pause" : ""));
			}
		}
		for (LineStats loop : data.loops) {
			if (loop.runningNow > 0 && loop.currentIteration >= 1000) {
				findings.add(String.format(Locale.ROOT, "The loop at %s (%s) is still running at iteration %,d (%,.0f iterations per second). If it should have ended, it may be a runaway loop.",
					loop.location(), shorten(loop.code, 80), loop.currentIteration, loop.iterationsPerSecond));
				break;
			}
		}
		double seconds = data.elapsedNanos() / 1e9;
		sortedVariables(data).stream()
			.filter(v -> !v.memoryOnly())
			.findFirst()
			.filter(v -> v.writes() / Math.max(1, seconds) >= 20)
			.ifPresent(v -> {
				LineStats writer = v.topWriter();
				findings.add(String.format(Locale.ROOT, "{%s} was saved %,d times (%.0f per second)%s. Every change of a saved variable is written to the variables file; use {_local} or {-memory} variables for values that don't need to survive a restart.",
					v.group, v.writes(), v.writes() / Math.max(1, seconds),
					writer == null ? "" : ", mostly by " + writer.location()));
			});
		synchronized (data.hangs) {
			if (!data.hangs.isEmpty())
				findings.add("The server froze " + data.hangs.size() + " time(s) during the capture. See \"Freezes\" for the script line that was running.");
		}
		if (silent != null && silent.loaded() > 0 && !silent.list().isEmpty())
			findings.add(String.format(Locale.ROOT, "%d of %d loaded triggers and functions never ran during the capture (see \"Never ran\").",
				silent.list().size(), silent.loaded()));
		if (!data.lineTiming)
			findings.add("Line timing was off (line-timing in hypertrace.yml), so only triggers, functions and events were timed.");
		if (data.otherThreadRuns.get() > 0)
			findings.add(String.format(Locale.ROOT, "%,d script runs happened on other threads (async events) and weren't timed; only the server thread is profiled.",
				data.otherThreadRuns.get()));
		return findings;
	}

	static double perTick(Capture data, long nanos) {
		return data.ticks == 0 ? nanos : (double) nanos / data.ticks;
	}

	// --- formatting ---

	static String ms(double nanos) {
		double ms = nanos / 1_000_000.0;
		if (ms >= 100)
			return String.format(Locale.ROOT, "%.0f ms", ms);
		if (ms >= 1)
			return String.format(Locale.ROOT, "%.2f ms", ms);
		return String.format(Locale.ROOT, "%.3f ms", ms);
	}

	static String micros(double nanos) {
		double micros = nanos / 1000.0;
		if (micros >= 1000)
			return ms(nanos);
		return String.format(Locale.ROOT, micros >= 10 ? "%.0f µs" : "%.1f µs", micros);
	}

	static String number(double value) {
		if (value >= 100)
			return String.format(Locale.ROOT, "%,.0f", value);
		return String.format(Locale.ROOT, value >= 10 ? "%.1f" : "%.2f", value);
	}

	static String shorten(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	// --- report data ---

	/**
	 * @return The report data as JSON. The report page ({@code hypertrace/report.html}) renders it; the same data is
	 * saved next to it for other tools.
	 */
	String json() {
		Json json = new Json();
		json.begin();
		json.field("version").value(1);
		json.field("kind").value(data.kind);
		json.field("generated").value(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
			.format(Instant.ofEpochMilli(data.startedMillis)));
		json.field("startedMillis").value(data.startedMillis);
		json.field("durationMs").value(data.elapsedNanos() / 1_000_000);
		json.field("server").value(Bukkit.getName() + ' ' + Bukkit.getMinecraftVersion());
		json.field("skript").value(Skript.getVersion() + " (HyperSkript)");
		json.field("lineTiming").value(data.lineTiming);
		json.field("ticks").value(data.ticks);
		json.field("lagTicks").value(data.lagTicks);
		json.field("tickTotalMs").value(data.tickTotalMs, 2);
		json.field("skriptNs").value(data.skriptNanos);
		json.field("tickSkriptNs").value(data.tickSkriptTotalNanos);
		json.field("overheadNs").value(data.overheadNanos);
		json.field("linesTimed").value(data.itemsTimed);
		json.field("otherThreadRuns").value(data.otherThreadRuns.get());
		json.field("activityCapped").value(data.activityCapped);
		json.field("gcPauseMs").value(data.gcPauseMs);
		json.field("gcPauseTicks").value(data.gcPauseTicks);

		int storedTicks = Math.min(data.ticks, Capture.MAX_TICKS);
		json.field("tickMs").array();
		for (int i = 0; i < storedTicks; i++)
			json.value(data.tickMs[i], 2);
		json.end();
		json.field("tickSkriptMs").array();
		for (int i = 0; i < storedTicks; i++)
			json.value(data.tickSkriptMs[i], 3);
		json.end();
		json.field("tickGcMs").array();
		for (int i = 0; i < storedTicks; i++)
			json.value(data.tickGcMs[i], 0);
		json.end();
		json.field("tickVariableWrites").array();
		for (int i = 0; i < storedTicks; i++)
			json.value(data.tickVariableWritesArray[i]);
		json.end();

		// triggers and functions (functions are triggers too); lines refer to them by index
		List<TriggerStats> triggers = new ArrayList<>(data.triggers.values());
		triggers.removeIf(t -> t.calls + t.resumes + t.checks == 0);
		triggers.sort(Comparator.comparingLong(TriggerStats::totalWithChecks).reversed());
		Map<TriggerStats, Integer> triggerIds = new IdentityHashMap<>();
		json.field("triggers").array();
		for (TriggerStats trigger : triggers) {
			triggerIds.put(trigger, triggerIds.size());
			json.begin();
			json.field("kind").value(trigger.kind.name().toLowerCase(Locale.ENGLISH));
			json.field("name").value(trigger.name);
			json.field("script").value(trigger.script);
			json.field("line").value(trigger.line);
			json.field("calls").value(trigger.calls);
			json.field("resumes").value(trigger.resumes);
			json.field("totalNs").value(trigger.total);
			json.field("ownNs").value(trigger.own);
			json.field("maxNs").value(trigger.max);
			json.field("checks").value(trigger.checks);
			json.field("passes").value(trigger.passes);
			json.field("checkNs").value(trigger.checkNanos);
			json.field("maxTickNs").value(trigger.maxTick);
			json.field("ticksActive").value(trigger.ticksActive);
			json.field("activity").array();
			for (int i = 0; i < trigger.activitySize; i++)
				json.value(trigger.activity[i]);
			json.end();
			json.end();
		}
		json.end();

		List<LineStats> lines = sortedLines(data);
		json.field("lines").array();
		for (LineStats line : lines) {
			json.begin();
			json.field("script").value(line.script);
			json.field("line").value(line.line);
			json.field("code").value(line.code);
			Integer trigger = line.trigger == null ? null : triggerIds.get(line.trigger);
			json.field("trigger").value(trigger == null ? -1 : trigger);
			json.field("section").value(line.section);
			json.field("calls").value(line.calls);
			json.field("totalNs").value(line.total);
			json.field("ownNs").value(line.own);
			json.field("maxNs").value(line.max);
			json.field("maxPauseNs").value(line.maxPause);
			json.field("blockNs").value(line.block);
			json.field("maxTickNs").value(line.maxTick);
			json.field("ticksActive").value(line.ticksActive);
			json.field("globalWrites").value(line.globalWrites);
			String why = diagnose(data, line);
			if (why != null)
				json.field("why").value(why);
			if (line.loop) {
				json.field("loop").begin();
				json.field("type").value(line.isWhile ? "while" : "loop");
				json.field("maxIterations").value(line.maxIterations);
				json.field("running").value(line.runningNow);
				json.field("peakConcurrent").value(line.peakConcurrent);
				json.field("currentIteration").value(line.currentIteration);
				json.field("iterationsPerSecond").value(line.iterationsPerSecond, 1);
				json.end();
			}
			json.end();
		}
		json.end();

		List<EventStats> events = new ArrayList<>(data.events.values());
		events.sort(Comparator.comparingLong((EventStats e) -> e.total).reversed());
		json.field("events").array();
		for (EventStats event : events) {
			json.begin();
			json.field("name").value(event.name);
			json.field("count").value(event.count);
			json.field("totalNs").value(event.total);
			json.field("maxNs").value(event.max);
			json.end();
		}
		json.end();

		json.field("worstTicks");
		ticks(json, data.worstTicks);
		json.field("worstSkriptTicks");
		ticks(json, data.worstSkriptTicks);

		json.field("variables").begin();
		json.field("creates").value(data.variableCreates);
		json.field("updates").value(data.variableUpdates);
		json.field("deletes").value(data.variableDeletes);
		json.field("groups").array();
		for (VariableStats variable : sortedVariables(data)) {
			json.begin();
			json.field("name").value(variable.group);
			json.field("saved").value(!variable.memoryOnly());
			json.field("creates").value(variable.creates);
			json.field("updates").value(variable.updates);
			json.field("deletes").value(variable.deletes);
			json.field("distinct").value(variable.names.size());
			json.field("distinctCapped").value(variable.names.size() >= VariableStats.MAX_NAMES);
			if (variable.lastType != null)
				json.field("lastType").value(variable.lastType);
			LineStats writer = variable.topWriter();
			if (writer != null) {
				json.field("writer").begin();
				json.field("script").value(writer.script);
				json.field("line").value(writer.line);
				json.field("code").value(writer.code);
				json.end();
			}
			json.end();
		}
		json.end();
		json.end();

		List<long[]> saves;
		synchronized (data.saves) {
			saves = new ArrayList<>(data.saves);
		}
		json.field("saves").array();
		for (long[] save : saves) {
			json.begin();
			json.field("startTick").value(data.tickAt(save[0]));
			json.field("endTick").value(data.tickAt(save[1]));
			json.field("second").value((save[0] - data.startedNanos) / 1e9, 1);
			json.field("ms").value((save[1] - save[0]) / 1e6, 1);
			json.field("bytes").value(save[2]);
			json.field("changes").value(save[3]);
			json.end();
		}
		json.end();

		json.field("silent").begin();
		json.field("loaded").value(silent.loaded());
		json.field("list").array();
		for (SilentTrigger trigger : silent.list()) {
			json.begin();
			json.field("kind").value(trigger.kind().name().toLowerCase(Locale.ENGLISH));
			json.field("name").value(trigger.name());
			json.field("script").value(trigger.script());
			json.field("line").value(trigger.line());
			json.field("checks").value(trigger.checks());
			json.end();
		}
		json.end();
		json.end();

		json.field("hangs").array();
		synchronized (data.hangs) {
			for (String hang : data.hangs)
				json.value(hang);
		}
		json.end();

		json.field("findings").array();
		for (String finding : findings(data, silent))
			json.value(finding);
		json.end();

		json.field("sources").begin();
		if (includeSources) {
			Set<String> scripts = new LinkedHashSet<>();
			for (LineStats line : lines)
				scripts.add(line.script);
			for (TriggerStats trigger : triggers)
				scripts.add(trigger.script);
			for (String script : scripts) {
				String source = readSource(data.scriptFiles.get(script));
				if (source != null)
					json.field(script).value(source);
			}
		}
		json.end();

		json.end();
		return json.toString();
	}

	private static void ticks(Json json, List<TickSnapshot> snapshots) {
		json.array();
		for (TickSnapshot tick : snapshots) {
			json.begin();
			json.field("tick").value(tick.tick - 1);
			json.field("second").value(tick.second, 1);
			json.field("ms").value(tick.ms, 2);
			json.field("skriptMs").value(tick.skriptMs, 3);
			json.field("gcMs").value(tick.gcMs);
			entries(json.field("triggers"), tick.triggers);
			entries(json.field("lines"), tick.lines);
			json.end();
		}
		json.end();
	}

	private static void entries(Json json, List<TickSnapshot.Entry> entries) {
		json.array();
		for (TickSnapshot.Entry entry : entries) {
			json.begin();
			json.field("label").value(entry.label());
			json.field("script").value(entry.script());
			json.field("line").value(entry.line());
			json.field("ns").value(entry.nanos());
			json.end();
		}
		json.end();
	}

	private static @Nullable String readSource(@Nullable Path path) {
		if (path == null)
			return null;
		try {
			if (Files.size(path) > MAX_SOURCE_BYTES)
				return null;
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * A minimal JSON writer. Commas are inserted automatically; {@link #end()} closes the innermost object or array.
	 */
	private static final class Json {

		private final StringBuilder out = new StringBuilder(256 * 1024);
		private final StringBuilder open = new StringBuilder();
		private boolean needsComma;

		private void comma() {
			if (needsComma)
				out.append(',');
		}

		Json begin() {
			comma();
			out.append('{');
			open.append('}');
			needsComma = false;
			return this;
		}

		Json array() {
			comma();
			out.append('[');
			open.append(']');
			needsComma = false;
			return this;
		}

		Json end() {
			int last = open.length() - 1;
			out.append(open.charAt(last));
			open.setLength(last);
			needsComma = true;
			return this;
		}

		Json field(String name) {
			comma();
			string(name);
			out.append(':');
			needsComma = false;
			return this;
		}

		Json value(String value) {
			comma();
			string(value);
			needsComma = true;
			return this;
		}

		Json value(long value) {
			comma();
			out.append(value);
			needsComma = true;
			return this;
		}

		Json value(boolean value) {
			comma();
			out.append(value);
			needsComma = true;
			return this;
		}

		Json value(double value, int decimals) {
			comma();
			if (Double.isNaN(value) || Double.isInfinite(value)) {
				out.append('0');
			} else {
				String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
				if (text.indexOf('.') >= 0) {
					int end = text.length();
					while (text.charAt(end - 1) == '0')
						end--;
					if (text.charAt(end - 1) == '.')
						end--;
					text = text.substring(0, end);
				}
				out.append(text.equals("-0") ? "0" : text);
			}
			needsComma = true;
			return this;
		}

		private void string(String value) {
			out.append('"');
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				switch (c) {
					case '"' -> out.append("\\\"");
					case '\\' -> out.append("\\\\");
					case '\n' -> out.append("\\n");
					case '\r' -> out.append("\\r");
					case '\t' -> out.append("\\t");
					default -> {
						if (c < 0x20)
							out.append(String.format("\\u%04x", (int) c));
						else
							out.append(c);
					}
				}
			}
			out.append('"');
		}

		@Override
		public String toString() {
			return out.toString();
		}

	}

}
