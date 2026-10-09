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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the findings and the HTML page of a capture. Must be used on the thread that owns the capture.
 */
final class HyperTraceReport {

	private static final int MAX_TABLE_ROWS = 200;
	private static final long MAX_SOURCE_BYTES = 2_000_000;

	private final Capture data;
	private final List<LineStats> lines;
	private final StringBuilder out = new StringBuilder(64 * 1024);
	private final Map<String, Integer> scriptIds = new HashMap<>();

	HyperTraceReport(Capture data) {
		this.data = data;
		this.lines = sortedLines(data);
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

	/**
	 * @return Why a line is expensive, in a few words, or null if nothing stands out.
	 */
	static @Nullable String diagnose(Capture data, LineStats line) {
		if (line.calls == 0)
			return null;
		double callsPerTick = data.ticks == 0 ? 0 : (double) line.calls / data.ticks;
		double avgMicros = line.total / 1000.0 / line.calls;
		double typicalTickNanos = line.ticksActive == 0 ? 0 : (double) line.own / line.ticksActive;
		List<String> reasons = new ArrayList<>();
		if (callsPerTick >= 50)
			reasons.add("runs very often (" + number(callsPerTick) + "× per tick)");
		if (avgMicros >= 500)
			reasons.add("is slow every time it runs (" + micros(line.total / line.calls) + " each)");
		if (line.maxTick >= 5_000_000 && line.maxTick > 10 * typicalTickNanos)
			reasons.add("causes lag spikes (up to " + ms(line.maxTick) + " in one tick)");
		if (line.loop && line.maxIterations >= 100)
			reasons.add("loops up to " + String.format(Locale.ROOT, "%,d", line.maxIterations) + " times per run");
		if (line.total > 2 * line.own && line.total - line.own > 1_000_000)
			reasons.add("spends most of its time in what it calls (functions, events or section bodies)");
		return reasons.isEmpty() ? null : String.join("; ", reasons);
	}

	/**
	 * @return The main findings of a capture, as plain sentences (most important first).
	 */
	static List<String> findings(Capture data) {
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
			String cause = worst.skriptMs >= worst.ms * 0.5
				? (worst.triggers.isEmpty() ? "" : ", mostly in " + worst.triggers.getFirst().label() + " (" + ms(worst.triggers.getFirst().nanos()) + ")")
				: "; Skript was not the main cause of that tick";
			findings.add(String.format(Locale.ROOT, "The slowest tick took %.1f ms (%.0f s into the capture). Skript used %.1f ms of it%s.",
				worst.ms, worst.second, worst.skriptMs, cause));
		}
		double seconds = data.elapsedNanos() / 1e9;
		data.variables.values().stream()
			.filter(v -> !v.memoryOnly())
			.max(Comparator.comparingLong(v -> v.writes + v.deletes))
			.filter(v -> (v.writes + v.deletes) / Math.max(1, seconds) >= 20)
			.ifPresent(v -> {
				LineStats writer = v.topWriter();
				findings.add(String.format(Locale.ROOT, "{%s} was saved %,d times (%.0f per second)%s. Every change of a saved variable is written to the variables file; use {_local} or {-memory} variables for values that don't need to survive a restart.",
					v.group, v.writes + v.deletes, (v.writes + v.deletes) / Math.max(1, seconds),
					writer == null ? "" : ", mostly by " + writer.location()));
			});
		synchronized (data.hangs) {
			if (!data.hangs.isEmpty())
				findings.add("The server froze " + data.hangs.size() + " time(s) during the capture. See \"Freezes\" for the script line that was running.");
		}
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

	private static String escape(String text) {
		StringBuilder builder = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '<' -> builder.append("&lt;");
				case '>' -> builder.append("&gt;");
				case '&' -> builder.append("&amp;");
				case '"' -> builder.append("&quot;");
				default -> builder.append(c);
			}
		}
		return builder.toString();
	}

	// --- page ---

	String html() {
		int id = 0;
		for (LineStats line : lines) {
			if (!scriptIds.containsKey(line.script))
				scriptIds.put(line.script, id++);
		}
		head();
		summary();
		section("Findings", "What stands out, most important first.");
		out.append("<ol class=findings>");
		for (String finding : findings(data))
			out.append("<li>").append(escape(finding)).append("</li>");
		out.append("</ol>");
		tickChart();
		lineTable();
		blockTable();
		triggerTable(false);
		triggerTable(true);
		eventTable();
		worstTicks();
		loopTable();
		variableTable();
		hangs();
		sources();
		out.append("<footer>HyperTrace · times are measured on the server thread. ");
		if (data.lineTiming && data.itemsTimed > 0) {
			out.append(String.format(Locale.ROOT, "Measuring itself took about %s per tick (%.0f ns per line run); it is left out of the lines' own times but included in trigger, function and tick totals.",
				ms(perTick(data, data.overheadNanos)), (double) data.overheadNanos / data.itemsTimed));
		}
		out.append("</footer></main>");
		script();
		out.append("</body></html>");
		return out.toString();
	}

	private void head() {
		String date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
			.format(Instant.ofEpochMilli(data.startedMillis));
		out.append("<!DOCTYPE html><html lang=en><head><meta charset=utf-8>")
			.append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
			.append("<title>HyperTrace report</title><style>").append(CSS).append("</style></head><body><main>")
			.append("<header><h1>HyperTrace report</h1><p class=muted>")
			.append(escape(date)).append(" · ")
			.append("Skript ").append(escape(String.valueOf(Skript.getVersion()))).append(" (HyperSkript) · ")
			.append(escape(Bukkit.getName() + ' ' + Bukkit.getMinecraftVersion()))
			.append(data.lineTiming ? " · line timing on" : " · line timing off")
			.append("</p></header>");
	}

	private void summary() {
		double seconds = data.elapsedNanos() / 1e9;
		double avgTick = data.ticks == 0 ? 0 : data.tickTotalMs / data.ticks;
		double skriptTick = data.ticks == 0 ? 0 : data.tickSkriptTotalNanos / 1_000_000.0 / data.ticks;
		float worst = data.worstTicks.isEmpty() ? 0 : data.worstTicks.getFirst().ms;
		out.append("<div class=cards>");
		card("Captured", String.format(Locale.ROOT, "%.0f s", seconds), String.format(Locale.ROOT, "%,d ticks", data.ticks));
		card("Average tick", String.format(Locale.ROOT, "%.2f ms", avgTick), "50 ms = lag");
		card("Skript per tick", String.format(Locale.ROOT, "%.2f ms", skriptTick),
			String.format(Locale.ROOT, "%.0f%% of the tick", avgTick == 0 ? 0 : 100 * skriptTick / avgTick));
		card("Slowest tick", String.format(Locale.ROOT, "%.1f ms", worst), String.format(Locale.ROOT, "%,d ticks over 50 ms", data.lagTicks));
		card("Lines timed", String.format(Locale.ROOT, "%,d", data.itemsTimed), String.format(Locale.ROOT, "%,d different lines", lines.size()));
		out.append("</div>");
	}

	private void card(String title, String value, String note) {
		out.append("<div class=card><div class=muted>").append(escape(title)).append("</div><div class=big>")
			.append(escape(value)).append("</div><div class=muted>").append(escape(note)).append("</div></div>");
	}

	private void section(String title, String description) {
		out.append("<h2>").append(escape(title)).append("</h2><p class=muted>").append(escape(description)).append("</p>");
	}

	private void tickChart() {
		int count = Math.min(data.ticks, Capture.MAX_TICKS);
		if (count < 2)
			return;
		section("Tick times", "Each point is a group of ticks (highest value shown). Blue: whole tick. Orange: Skript's part. The dashed line is 50 ms.");
		int points = Math.min(count, 1200);
		double perPoint = (double) count / points;
		float[] tick = new float[points];
		float[] skript = new float[points];
		float max = 50;
		for (int p = 0; p < points; p++) {
			int from = (int) (p * perPoint);
			int to = Math.max(from + 1, (int) ((p + 1) * perPoint));
			for (int i = from; i < to && i < count; i++) {
				tick[p] = Math.max(tick[p], data.tickMs[i]);
				skript[p] = Math.max(skript[p], data.tickSkriptMs[i]);
			}
			max = Math.max(max, tick[p]);
		}
		int width = 1200, height = 220;
		out.append("<svg class=chart viewBox=\"0 0 ").append(width).append(' ').append(height)
			.append("\" preserveAspectRatio=none role=img aria-label=\"Tick times\">");
		double lagY = height - 50 / max * height;
		out.append("<line x1=0 x2=").append(width).append(" y1=").append(fmt(lagY)).append(" y2=").append(fmt(lagY))
			.append(" class=lag />");
		polyline(tick, max, width, height, "tick");
		polyline(skript, max, width, height, "skript");
		out.append("</svg><p class=muted>Scale: 0 – ").append(fmt(max)).append(" ms</p>");
	}

	private void polyline(float[] values, float max, int width, int height, String cls) {
		out.append("<polyline class=").append(cls).append(" points=\"");
		for (int i = 0; i < values.length; i++) {
			double x = values.length == 1 ? 0 : (double) i * width / (values.length - 1);
			double y = height - values[i] / max * height;
			out.append(fmt(x)).append(',').append(fmt(y)).append(' ');
		}
		out.append("\" />");
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private String link(String script, int line) {
		Integer id = scriptIds.get(script);
		String text = escape(script + ':' + (line > 0 ? line : "?"));
		if (id == null || line <= 0)
			return "<span class=loc>" + text + "</span>";
		return "<a class=loc href=\"#s" + id + "-" + line + "\" data-open=\"s" + id + "\">" + text + "</a>";
	}

	private void tableStart(String... headers) {
		out.append("<div class=scroll><table class=sortable><thead><tr>");
		for (String header : headers)
			out.append("<th>").append(header).append("</th>");
		out.append("</tr></thead><tbody>");
	}

	private void tableEnd() {
		out.append("</tbody></table></div>");
	}

	private void cell(String html, double sortValue) {
		out.append("<td data-v=\"").append(sortValue).append("\">").append(html).append("</td>");
	}

	private void textCell(String html) {
		out.append("<td>").append(html).append("</td>");
	}

	private void lineTable() {
		if (lines.isEmpty())
			return;
		section("Slowest lines", "Own time: time spent in the line itself, without the functions, events and section bodies it runs. "
			+ "Total: including them. Per tick values are averages over the whole capture. Click a column to sort, click a location to see it in its script.");
		tableStart("#", "Location", "Code", "Own / tick", "% of Skript", "Total / tick", "Runs / tick", "Avg run", "Slowest run", "Worst tick", "Why");
		int rank = 0;
		for (LineStats line : lines) {
			if (++rank > MAX_TABLE_ROWS)
				break;
			out.append("<tr>");
			cell(String.valueOf(rank), rank);
			cell(link(line.script, line.line), rank);
			textCell("<code>" + escape(shorten(line.code, 120)) + "</code>");
			cell(ms(perTick(data, line.own)), line.own);
			cell(bar(data.share(line.own)), data.share(line.own));
			cell(ms(perTick(data, line.total)), line.total);
			cell(number(data.ticks == 0 ? line.calls : (double) line.calls / data.ticks), line.calls);
			cell(micros((double) line.total / line.calls), (double) line.total / line.calls);
			cell(ms(line.max), line.max);
			cell(ms(line.maxTick), line.maxTick);
			String why = diagnose(data, line);
			textCell(why == null ? "" : escape(why));
			out.append("</tr>");
		}
		tableEnd();
	}

	private String bar(double percent) {
		return "<span class=bar><span style=\"width:" + fmt(Math.min(100, percent)) + "%\"></span></span> " + fmt(percent) + "%";
	}

	private void blockTable() {
		List<LineStats> blocks = sortedBlocks(data);
		if (blocks.isEmpty())
			return;
		section("Slowest blocks", "Sections (loops, ifs, other sections) with the time of every line inside them, including what those lines call.");
		tableStart("Location", "Section", "Total / tick", "% of Skript", "Runs / tick", "Most iterations");
		int rows = 0;
		for (LineStats block : blocks) {
			if (++rows > MAX_TABLE_ROWS)
				break;
			out.append("<tr>");
			cell(link(block.script, block.line), rows);
			textCell("<code>" + escape(shorten(block.code, 120)) + "</code>");
			cell(ms(perTick(data, block.block)), block.block);
			cell(bar(data.share(block.block)), data.share(block.block));
			cell(number(data.ticks == 0 ? block.calls : (double) block.calls / data.ticks), block.calls);
			cell(block.loop ? String.format(Locale.ROOT, "%,d", block.maxIterations) : "", block.maxIterations);
			out.append("</tr>");
		}
		tableEnd();
	}

	private void triggerTable(boolean functions) {
		List<TriggerStats> triggers = sortedTriggers(data, functions);
		if (triggers.isEmpty())
			return;
		if (functions) {
			section("Functions", "Every function call, including the functions it calls in turn.");
			tableStart("Function", "Location", "Calls", "Total / tick", "% of Skript", "Avg call", "Slowest call", "Worst tick");
		} else {
			section("Triggers", "Events, commands and other triggers. Total includes everything the trigger runs, its event filter "
				+ "(e.g. \"on break of stone\" checking the block) and the parts after a wait. Checked / ran shows how often the filter let it run.");
			tableStart("Trigger", "Location", "Ran", "Checked", "Total / tick", "% of Skript", "Filter / tick", "Avg run", "Slowest run", "Worst tick");
		}
		int rows = 0;
		for (TriggerStats trigger : triggers) {
			if (++rows > MAX_TABLE_ROWS)
				break;
			long total = trigger.totalWithChecks();
			out.append("<tr>");
			textCell("<code>" + escape(shorten(trigger.name, 100)) + "</code>");
			cell(link(trigger.script, trigger.line), rows);
			cell(String.format(Locale.ROOT, "%,d", trigger.calls) + (trigger.resumes > 0 ? " <span class=muted>(+" + trigger.resumes + " after waits)</span>" : ""), trigger.calls);
			if (!functions)
				cell(trigger.checks == 0 ? "" : String.format(Locale.ROOT, "%,d", trigger.checks), trigger.checks);
			cell(ms(perTick(data, total)), total);
			cell(bar(data.share(total)), data.share(total));
			if (!functions)
				cell(trigger.checks == 0 ? "" : ms(perTick(data, trigger.checkNanos)), trigger.checkNanos);
			long runs = trigger.calls + trigger.resumes;
			cell(runs == 0 ? "" : micros((double) trigger.total / runs), runs == 0 ? 0 : (double) trigger.total / runs);
			cell(ms(trigger.max), trigger.max);
			cell(ms(trigger.maxTick), trigger.maxTick);
			out.append("</tr>");
		}
		tableEnd();
	}

	private void eventTable() {
		if (data.events.isEmpty())
			return;
		List<EventStats> events = new ArrayList<>(data.events.values());
		events.sort(Comparator.comparingLong((EventStats e) -> e.total).reversed());
		section("Events", "Bukkit events Skript handled: every trigger of the event, with filters.");
		tableStart("Event", "Fired", "Fired / tick", "Total / tick", "% of Skript", "Avg", "Slowest");
		int rows = 0;
		for (EventStats event : events) {
			if (++rows > MAX_TABLE_ROWS)
				break;
			out.append("<tr>");
			textCell(escape(event.name));
			cell(String.format(Locale.ROOT, "%,d", event.count), event.count);
			cell(number(data.ticks == 0 ? event.count : (double) event.count / data.ticks), event.count);
			cell(ms(perTick(data, event.total)), event.total);
			cell(bar(data.share(event.total)), data.share(event.total));
			cell(micros((double) event.total / event.count), (double) event.total / event.count);
			cell(ms(event.max), event.max);
			out.append("</tr>");
		}
		tableEnd();
	}

	private void worstTicks() {
		if (data.worstTicks.isEmpty())
			return;
		section("Slowest ticks", "The slowest ticks of the capture and what Skript ran in them (lines by own time).");
		ticks(data.worstTicks);
		if (!data.worstSkriptTicks.isEmpty() && data.worstSkriptTicks.getFirst() != data.worstTicks.getFirst()) {
			out.append("<h3>Ticks where Skript used the most time</h3>");
			ticks(data.worstSkriptTicks);
		}
	}

	private void ticks(List<TickSnapshot> snapshots) {
		tableStart("Tick", "At", "Tick time", "Skript", "Triggers", "Lines");
		for (TickSnapshot tick : snapshots) {
			out.append("<tr>");
			cell(String.format(Locale.ROOT, "#%,d", tick.tick), tick.tick);
			cell(String.format(Locale.ROOT, "%.0f s", tick.second), tick.second);
			cell(String.format(Locale.ROOT, "%.1f ms", tick.ms), tick.ms);
			cell(String.format(Locale.ROOT, "%.1f ms", tick.skriptMs), tick.skriptMs);
			textCell(entries(tick.triggers));
			textCell(entries(tick.lines));
			out.append("</tr>");
		}
		tableEnd();
	}

	private static String entries(List<TickSnapshot.Entry> entries) {
		StringBuilder builder = new StringBuilder();
		for (TickSnapshot.Entry entry : entries) {
			builder.append("<div><b>").append(ms(entry.nanos())).append("</b> <code>")
				.append(escape(shorten(entry.label(), 110))).append("</code></div>");
		}
		return builder.toString();
	}

	private void loopTable() {
		List<LineStats> loops = new ArrayList<>();
		for (LineStats line : lines) {
			if (line.loop)
				loops.add(line);
		}
		if (loops.isEmpty())
			return;
		loops.sort(Comparator.comparingLong((LineStats line) -> line.block).reversed());
		section("Loops", "Every loop that ran, with the total time of its body.");
		tableStart("Location", "Loop", "Iterations", "Most iterations in one run", "Total / tick", "% of Skript");
		int rows = 0;
		for (LineStats loop : loops) {
			if (++rows > MAX_TABLE_ROWS)
				break;
			out.append("<tr>");
			cell(link(loop.script, loop.line), rows);
			textCell("<code>" + escape(shorten(loop.code, 120)) + "</code>");
			cell(String.format(Locale.ROOT, "%,d", loop.calls), loop.calls);
			cell(String.format(Locale.ROOT, "%,d", loop.maxIterations), loop.maxIterations);
			cell(ms(perTick(data, loop.block)), loop.block);
			cell(bar(data.share(loop.block)), data.share(loop.block));
			out.append("</tr>");
		}
		tableEnd();
	}

	private void variableTable() {
		if (data.variables.isEmpty())
			return;
		List<VariableStats> variables = new ArrayList<>(data.variables.values());
		variables.sort(Comparator.comparingLong((VariableStats v) -> v.writes + v.deletes).reversed());
		double seconds = Math.max(1, data.elapsedNanos() / 1e9);
		section("Global variable writes", "Changes to global variables, grouped by list. Saved variables are written to the variables file "
			+ "(or database); {-memory} variables are not.");
		tableStart("Variable", "Saved", "Set", "Deleted", "Per second", "Different names", "Mostly written by");
		int rows = 0;
		for (VariableStats variable : variables) {
			if (++rows > MAX_TABLE_ROWS)
				break;
			LineStats writer = variable.topWriter();
			out.append("<tr>");
			textCell("<code>{" + escape(variable.group) + "}</code>");
			textCell(variable.memoryOnly() ? "no" : "yes");
			cell(String.format(Locale.ROOT, "%,d", variable.writes), variable.writes);
			cell(String.format(Locale.ROOT, "%,d", variable.deletes), variable.deletes);
			cell(number((variable.writes + variable.deletes) / seconds), variable.writes + variable.deletes);
			cell(String.format(Locale.ROOT, "%,d", variable.names.size()) + (variable.names.size() >= VariableStats.MAX_NAMES ? "+" : ""), variable.names.size());
			textCell(writer == null ? "" : link(writer.script, writer.line) + " <code>" + escape(shorten(writer.code, 80)) + "</code>");
			out.append("</tr>");
		}
		tableEnd();
	}

	private void hangs() {
		List<String> hangs;
		synchronized (data.hangs) {
			hangs = new ArrayList<>(data.hangs);
		}
		if (hangs.isEmpty())
			return;
		section("Freezes", "Moments where the server stopped ticking, with the script lines that were running.");
		for (String hang : hangs)
			out.append("<pre>").append(escape(hang)).append("</pre>");
	}

	private void sources() {
		if (lines.isEmpty())
			return;
		Map<String, Map<Integer, LineStats>> byScript = new HashMap<>();
		Map<String, Long> scriptTotals = new HashMap<>();
		long maxOwn = 1;
		for (LineStats line : lines) {
			if (line.line <= 0)
				continue;
			byScript.computeIfAbsent(line.script, s -> new HashMap<>()).put(line.line, line);
			scriptTotals.merge(line.script, line.own, Long::sum);
			maxOwn = Math.max(maxOwn, line.own);
		}
		List<String> scripts = new ArrayList<>(byScript.keySet());
		scripts.sort(Comparator.comparingLong((String s) -> scriptTotals.get(s)).reversed());
		section("Scripts", "Every script that ran, with each line colored by its own time. Numbers on the right: own time per tick, "
			+ "runs per tick, and for sections the total of the block.");
		for (String script : scripts) {
			Integer id = scriptIds.get(script);
			List<String> source = readSource(script);
			Map<Integer, LineStats> stats = byScript.get(script);
			out.append("<details id=s").append(id).append("><summary><code>").append(escape(script)).append("</code> · ")
				.append(ms(perTick(data, scriptTotals.get(script)))).append(" per tick · ")
				.append(fmt(data.share(scriptTotals.get(script)))).append("% of Skript</summary><div class=scroll><table class=src>");
			int lastLine = source != null ? source.size() : stats.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
			for (int number = 1; number <= lastLine; number++) {
				LineStats line = stats.get(number);
				String code = source != null ? source.get(number - 1) : line != null ? line.code : "";
				double heat = line == null ? 0 : Math.sqrt((double) line.own / maxOwn);
				out.append("<tr id=s").append(id).append('-').append(number);
				if (line != null)
					out.append(" class=hit style=\"--heat:").append(fmt(heat * 100)).append("%\"");
				out.append("><td class=num>").append(number).append("</td><td class=code><pre>").append(escape(code)).append("</pre></td><td class=stat>");
				if (line != null) {
					out.append(ms(perTick(data, line.own))).append(" · ")
						.append(number(data.ticks == 0 ? line.calls : (double) line.calls / data.ticks)).append("×");
					if (line.section && line.block > 0)
						out.append(" · block ").append(ms(perTick(data, line.block)));
				}
				out.append("</td></tr>");
			}
			out.append("</table></div></details>");
		}
		if (scripts.isEmpty())
			out.append("<p class=muted>No line numbers were available.</p>");
	}

	private @Nullable List<String> readSource(String script) {
		Path path = data.scriptFiles.get(script);
		if (path == null)
			return null;
		try {
			if (Files.size(path) > MAX_SOURCE_BYTES)
				return null;
			return Files.readAllLines(path, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private void script() {
		out.append("<script>").append(JS).append("</script>");
	}

	private static final String CSS = """
		:root{--bg:#f7f7f9;--panel:#fff;--text:#1d1d22;--muted:#6b6b76;--line:#e3e3e8;--accent:#2f6fde;--hot:#e5533d;--skript:#e8912d;--code:#f0f0f4}
		@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#141418;--panel:#1d1d23;--text:#e9e9ee;--muted:#9a9aa6;--line:#2e2e37;--accent:#6f9cf0;--hot:#f0705c;--skript:#f0a54a;--code:#26262e}}
		:root[data-theme=dark]{--bg:#141418;--panel:#1d1d23;--text:#e9e9ee;--muted:#9a9aa6;--line:#2e2e37;--accent:#6f9cf0;--hot:#f0705c;--skript:#f0a54a;--code:#26262e}
		*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
		main{max-width:1400px;margin:0 auto;padding:24px 16px 48px}h1{margin:0 0 4px;font-size:26px}h2{margin:36px 0 2px;font-size:19px}h3{margin:20px 0 6px;font-size:15px}
		.muted{color:var(--muted);margin:2px 0 10px}code,pre{font:12.5px/1.45 ui-monospace,Consolas,monospace}pre{margin:0;white-space:pre-wrap}
		.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-top:18px}
		.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:12px 14px}.card .muted{margin:0}.big{font-size:24px;font-weight:650}
		.findings{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 18px 14px 36px;margin:0}.findings li{margin:4px 0}
		.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--panel)}
		table{border-collapse:collapse;width:100%}th,td{padding:6px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
		th{position:sticky;top:0;background:var(--panel);cursor:pointer;white-space:nowrap;font-weight:600}th:hover{color:var(--accent)}
		td{white-space:nowrap}td:has(code){white-space:normal;min-width:220px}tbody tr:hover{background:var(--code)}
		a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
		.bar{display:inline-block;width:60px;height:8px;background:var(--code);border-radius:4px;vertical-align:middle;overflow:hidden}.bar span{display:block;height:100%;background:var(--hot)}
		.chart{width:100%;height:220px;background:var(--panel);border:1px solid var(--line);border-radius:10px}
		.chart polyline{fill:none;stroke-width:1.5;vector-effect:non-scaling-stroke}.chart .tick{stroke:var(--accent)}.chart .skript{stroke:var(--skript)}
		.chart .lag{stroke:var(--hot);stroke-dasharray:6 4;vector-effect:non-scaling-stroke}
		details{background:var(--panel);border:1px solid var(--line);border-radius:10px;margin:8px 0}summary{padding:10px 14px;cursor:pointer}
		details .scroll{border:0;border-top:1px solid var(--line);border-radius:0 0 10px 10px}
		.src td{border:0;padding:0 10px}.src .num{color:var(--muted);text-align:right;user-select:none;width:1%}.src .code{white-space:pre;width:100%}
		.src .stat{color:var(--muted);font:12px ui-monospace,Consolas,monospace;text-align:right;width:1%}
		.src tr.hit{background:linear-gradient(90deg,color-mix(in srgb,var(--hot) var(--heat),transparent) 0 4px,color-mix(in srgb,var(--hot) calc(var(--heat) * .35),transparent) 4px)}
		.src tr.hit .stat{color:var(--text)}.src tr:target{outline:2px solid var(--accent)}
		footer{margin-top:40px;color:var(--muted);font-size:12px}
		""";

	private static final String JS = """
		document.querySelectorAll('table.sortable').forEach(t=>{t.querySelectorAll('th').forEach((th,i)=>{th.addEventListener('click',()=>{
		const body=t.tBodies[0],rows=[...body.rows],desc=th.dataset.dir!=='desc';t.querySelectorAll('th').forEach(h=>delete h.dataset.dir);th.dataset.dir=desc?'desc':'asc';
		rows.sort((a,b)=>{const x=a.cells[i],y=b.cells[i],vx=x.dataset.v,vy=y.dataset.v;
		const r=(vx!==undefined&&vy!==undefined)?(+vx)-(+vy):x.textContent.localeCompare(y.textContent);return desc?-r:r});rows.forEach(r=>body.appendChild(r))})})});
		function openTarget(){const id=location.hash.slice(1);if(!id)return;const row=document.getElementById(id);if(row){const d=row.closest('details');if(d)d.open=true;row.scrollIntoView({block:'center'})}}
		window.addEventListener('hashchange',openTarget);openTarget();
		""";

}
