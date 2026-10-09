package org.skriptlang.skript.hypertrace;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.LoopSection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.hypertrace.Capture.EventStats;
import org.skriptlang.skript.hypertrace.Capture.LineStats;
import org.skriptlang.skript.hypertrace.Capture.TriggerStats;
import org.skriptlang.skript.hypertrace.Capture.VariableStats;
import org.skriptlang.skript.hypertrace.HyperTrace.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.skriptlang.skript.hypertrace.HyperTraceReport.micros;
import static org.skriptlang.skript.hypertrace.HyperTraceReport.ms;
import static org.skriptlang.skript.hypertrace.HyperTraceReport.number;
import static org.skriptlang.skript.hypertrace.HyperTraceReport.perTick;
import static org.skriptlang.skript.hypertrace.HyperTraceReport.shorten;

/**
 * {@code /hypertrace}: starts and stops captures and shows their results in chat.
 */
final class HyperTraceCommand implements TabExecutor {

	private static final TextColor ACCENT = TextColor.color(0x6f9cf0);
	private static final TextColor HOT = TextColor.color(0xf0705c);
	private static final List<String> SUBCOMMANDS = List.of("start", "stop", "status", "lines", "blocks", "triggers",
		"functions", "events", "variables", "loops", "report", "clip", "rolling", "reset", "help");

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		Settings settings = HyperTrace.loadSettings(Skript.getInstance());
		if (!settings.enabled()) {
			sender.sendMessage(Component.text("HyperTrace is turned off (enabled: false in plugins/Skript/hypertrace.yml).", NamedTextColor.RED));
			return true;
		}
		String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ENGLISH);
		int count = args.length > 1 ? parseInt(args[1], settings.chatLines()) : settings.chatLines();
		switch (sub) {
			case "start" -> start(sender, settings, args);
			case "stop" -> stop(sender, settings);
			case "status" -> status(sender, settings);
			case "lines" -> lines(sender, count, false);
			case "blocks" -> lines(sender, count, true);
			case "triggers" -> triggers(sender, count, false);
			case "functions" -> triggers(sender, count, true);
			case "events" -> events(sender, count);
			case "variables" -> variables(sender, count);
			case "loops" -> loops(sender);
			case "report" -> report(sender);
			case "clip" -> clip(sender);
			case "rolling" -> rolling(sender, settings, args);
			case "reset" -> reset(sender);
			default -> help(sender, label);
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1) {
			List<String> matches = new ArrayList<>();
			for (String sub : SUBCOMMANDS) {
				if (sub.startsWith(args[0].toLowerCase(Locale.ENGLISH)))
					matches.add(sub);
			}
			return matches;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("start"))
			return List.of("30s", "1m", "5m");
		if (args.length == 2 && args[0].equalsIgnoreCase("rolling"))
			return List.of("on", "off");
		return List.of();
	}

	private static void help(CommandSender sender, String label) {
		sender.sendMessage(title("HyperTrace", "finds what slows your scripts down"));
		helpLine(sender, label, "start [time]", "start measuring (e.g. start 60s; stops by itself after the time)");
		helpLine(sender, label, "stop", "stop and save the full report (HTML + JSON)");
		helpLine(sender, label, "status", "summary of the running capture");
		helpLine(sender, label, "lines [n]", "the slowest script lines");
		helpLine(sender, label, "blocks [n]", "the slowest loops and sections, with their contents");
		helpLine(sender, label, "triggers / functions [n]", "the slowest events, commands and functions");
		helpLine(sender, label, "events [n]", "the Bukkit events that cost Skript the most");
		helpLine(sender, label, "variables [n]", "the global variables written most often");
		helpLine(sender, label, "loops", "loops running right now (e.g. ones with waits)");
		helpLine(sender, label, "report", "save the report without stopping");
		helpLine(sender, label, "rolling on/off", "always-on capture that saves lag spikes by itself");
		helpLine(sender, label, "clip", "save a report of the last minute or two (rolling mode)");
		helpLine(sender, label, "reset", "start over");
	}

	private static void helpLine(CommandSender sender, String label, String usage, String description) {
		sender.sendMessage(Component.text(" /" + label + " " + usage, ACCENT)
			.clickEvent(ClickEvent.suggestCommand("/" + label + " " + usage.split(" ")[0]))
			.append(Component.text(" - " + description, NamedTextColor.GRAY)));
	}

	private static void start(CommandSender sender, Settings settings, String[] args) {
		if (HyperTrace.isRolling()) {
			sender.sendMessage(Component.text("The rolling buffer is on, so everything is already being measured. Use /hypertrace clip for a report, or /hypertrace rolling off first.", NamedTextColor.RED));
			return;
		}
		if (HyperTrace.current() != null) {
			sender.sendMessage(Component.text("A capture is already running. Use /hypertrace status or stop.", NamedTextColor.RED));
			return;
		}
		long seconds = args.length > 1 ? parseDuration(args[1]) : 0;
		if (args.length > 1 && seconds <= 0) {
			sender.sendMessage(Component.text("Unknown time '" + args[1] + "'. Examples: 30s, 5m, 1h.", NamedTextColor.RED));
			return;
		}
		Runnable start = () -> {
			HyperTrace.start(settings, seconds, sender);
			long limit = seconds > 0 ? seconds : settings.maxCaptureMinutes() * 60L;
			sender.sendMessage(title("HyperTrace", "capture started"
				+ (limit > 0 ? ", stops by itself after " + duration(limit) : "")
				+ (settings.lineTiming() ? "" : " (line timing is off)")));
			sender.sendMessage(Component.text(" Play normally, then use /hypertrace status, lines or stop.", NamedTextColor.GRAY));
		};
		if (Bukkit.isPrimaryThread())
			start.run();
		else
			Bukkit.getScheduler().runTask(Skript.getInstance(), start);
	}

	private static void stop(CommandSender sender, Settings settings) {
		if (HyperTrace.current() == null) {
			sender.sendMessage(Component.text("No capture is running. Use /hypertrace start.", NamedTextColor.RED));
			return;
		}
		boolean wasRolling = HyperTrace.isRolling();
		File report = HyperTrace.stop(true);
		Capture capture = HyperTrace.currentOrLast();
		if (capture != null)
			summary(sender, capture, settings.chatLines(), wasRolling ? "rolling buffer stopped (until restart; use rolling off to keep it off)" : "capture stopped");
		sendReportLocation(sender, report);
	}

	static void announceAutoStop(Capture capture, @Nullable File report) {
		int chatLines = HyperTrace.settings().chatLines();
		for (CommandSender receiver : receivers()) {
			summary(receiver, capture, chatLines, "capture finished");
			sendReportLocation(receiver, report);
		}
	}

	static void announceSpike(@Nullable Capture clip, File report, double tickMs) {
		for (CommandSender receiver : receivers()) {
			receiver.sendMessage(title("HyperTrace", String.format(Locale.ROOT, "lag spike (%.0f ms tick), clip saved", tickMs)));
			if (clip != null && !clip.worstTicks.isEmpty()) {
				Capture.TickSnapshot worst = clip.worstTicks.getFirst();
				if (!worst.lines.isEmpty()) {
					Capture.TickSnapshot.Entry line = worst.lines.getFirst();
					receiver.sendMessage(Component.text(String.format(Locale.ROOT, " Slowest line in that tick: %s:%d  %s (%s)",
						line.script(), line.line(), shorten(line.label(), 60), ms(line.nanos())), NamedTextColor.GRAY));
				}
			}
			sendReportLocation(receiver, report);
		}
	}

	private static List<CommandSender> receivers() {
		List<CommandSender> receivers = new ArrayList<>();
		receivers.add(Bukkit.getConsoleSender());
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.hasPermission("skript.hypertrace"))
				receivers.add(player);
		}
		return receivers;
	}

	private static void status(CommandSender sender, Settings settings) {
		Capture capture = HyperTrace.currentOrLast();
		if (capture == null) {
			sender.sendMessage(Component.text("No capture yet. Use /hypertrace start (or rolling on).", NamedTextColor.RED));
			return;
		}
		String state = HyperTrace.isRolling() ? "rolling buffer, current segment" : capture.isRunning() ? "capturing" : "last capture";
		summary(sender, capture, settings.chatLines(), state);
	}

	private static void summary(CommandSender sender, Capture capture, int count, String state) {
		double seconds = capture.elapsedNanos() / 1e9;
		sender.sendMessage(title("HyperTrace", String.format(Locale.ROOT, "%s, %.0f s, %,d ticks", state, seconds, capture.ticks)));
		List<String> findings = HyperTraceReport.findings(capture, null);
		for (int i = 0; i < Math.min(3, findings.size()); i++)
			sender.sendMessage(Component.text(" • " + findings.get(i), NamedTextColor.GRAY));
		lines(sender, capture, count, false);
	}

	private static @Nullable Capture require(CommandSender sender) {
		Capture capture = HyperTrace.currentOrLast();
		if (capture == null)
			sender.sendMessage(Component.text("No capture yet. Use /hypertrace start.", NamedTextColor.RED));
		return capture;
	}

	private static void lines(CommandSender sender, int count, boolean blocks) {
		Capture capture = require(sender);
		if (capture != null)
			lines(sender, capture, count, blocks);
	}

	private static void lines(CommandSender sender, Capture capture, int count, boolean blocks) {
		if (!capture.lineTiming) {
			sender.sendMessage(Component.text(" Line timing is off (line-timing in hypertrace.yml).", NamedTextColor.GRAY));
			return;
		}
		List<LineStats> lines = blocks ? HyperTraceReport.sortedBlocks(capture) : HyperTraceReport.sortedLines(capture);
		if (lines.isEmpty()) {
			sender.sendMessage(Component.text(" No script lines ran yet.", NamedTextColor.GRAY));
			return;
		}
		sender.sendMessage(Component.text(blocks
			? " Slowest blocks (time of everything inside, per tick):"
			: " Slowest lines (own time per tick, hover for details):", NamedTextColor.WHITE));
		for (int i = 0; i < Math.min(count, lines.size()); i++) {
			LineStats line = lines.get(i);
			long time = blocks ? line.block : line.own;
			String why = HyperTraceReport.diagnose(capture, line);
			Component hover = Component.text(line.location(), ACCENT)
				.append(Component.newline()).append(Component.text(shorten(line.code, 200), NamedTextColor.WHITE))
				.append(Component.newline()).append(detail("Own time per tick", ms(perTick(capture, line.own))))
				.append(Component.newline()).append(detail("Total per tick", ms(perTick(capture, line.total))))
				.append(line.section ? Component.newline().append(detail("Block per tick", ms(perTick(capture, line.block)))) : Component.empty())
				.append(Component.newline()).append(detail("Runs per tick", number(capture.ticks == 0 ? line.calls : (double) line.calls / capture.ticks)))
				.append(Component.newline()).append(detail("Average run", micros((double) line.total / line.calls)))
				.append(Component.newline()).append(detail("Slowest run", ms(line.max)))
				.append(Component.newline()).append(detail("Worst tick", ms(line.maxTick)))
				.append(line.loop ? Component.newline().append(detail("Most iterations", String.format(Locale.ROOT, "%,d", line.maxIterations))) : Component.empty())
				.append(line.globalWrites > 0 ? Component.newline().append(detail("Global variable writes", String.format(Locale.ROOT, "%,d", line.globalWrites))) : Component.empty())
				.append(why == null ? Component.empty() : Component.newline().append(Component.text("Why: " + why, HOT)));
			sender.sendMessage(rank(i)
				.append(Component.text(String.format(Locale.ROOT, "%-9s", ms(perTick(capture, time))), HOT))
				.append(Component.text(String.format(Locale.ROOT, " %3.0f%% ", capture.share(time)), NamedTextColor.GRAY))
				.append(Component.text(line.location(), ACCENT))
				.append(Component.text("  " + shorten(line.code.strip(), 60), NamedTextColor.WHITE))
				.hoverEvent(HoverEvent.showText(hover)));
		}
	}

	private static void triggers(CommandSender sender, int count, boolean functions) {
		Capture capture = require(sender);
		if (capture == null)
			return;
		List<TriggerStats> triggers = HyperTraceReport.sortedTriggers(capture, functions);
		if (triggers.isEmpty()) {
			sender.sendMessage(Component.text(functions ? " No functions ran yet." : " No triggers ran yet.", NamedTextColor.GRAY));
			return;
		}
		sender.sendMessage(Component.text(functions ? " Slowest functions (per tick):" : " Slowest triggers (per tick, including event filters):", NamedTextColor.WHITE));
		for (int i = 0; i < Math.min(count, triggers.size()); i++) {
			TriggerStats trigger = triggers.get(i);
			long total = trigger.totalWithChecks();
			Component hover = Component.text(trigger.label(), ACCENT)
				.append(Component.newline()).append(detail("Ran", String.format(Locale.ROOT, "%,d times", trigger.calls)))
				.append(trigger.resumes > 0 ? Component.newline().append(detail("Continued after waits", String.format(Locale.ROOT, "%,d times", trigger.resumes))) : Component.empty())
				.append(trigger.checks > 0 ? Component.newline().append(detail("Event filter", String.format(Locale.ROOT, "checked %,d times, %s per tick", trigger.checks, ms(perTick(capture, trigger.checkNanos))))) : Component.empty())
				.append(Component.newline()).append(detail("Own lines per tick", ms(perTick(capture, trigger.own))))
				.append(Component.newline()).append(detail("Slowest run", ms(trigger.max)))
				.append(Component.newline()).append(detail("Worst tick", ms(trigger.maxTick)));
			sender.sendMessage(rank(i)
				.append(Component.text(String.format(Locale.ROOT, "%-9s", ms(perTick(capture, total))), HOT))
				.append(Component.text(String.format(Locale.ROOT, " %3.0f%% ", capture.share(total)), NamedTextColor.GRAY))
				.append(Component.text(shorten(trigger.name, 50), NamedTextColor.WHITE))
				.append(Component.text("  " + trigger.location(), ACCENT))
				.hoverEvent(HoverEvent.showText(hover)));
		}
	}

	private static void events(CommandSender sender, int count) {
		Capture capture = require(sender);
		if (capture == null)
			return;
		List<EventStats> events = new ArrayList<>(capture.events.values());
		events.sort(Comparator.comparingLong((EventStats e) -> e.total).reversed());
		if (events.isEmpty()) {
			sender.sendMessage(Component.text(" No events with Skript triggers fired yet.", NamedTextColor.GRAY));
			return;
		}
		sender.sendMessage(Component.text(" Events by Skript time (per tick, all triggers of the event):", NamedTextColor.WHITE));
		for (int i = 0; i < Math.min(count, events.size()); i++) {
			EventStats event = events.get(i);
			sender.sendMessage(rank(i)
				.append(Component.text(String.format(Locale.ROOT, "%-9s", ms(perTick(capture, event.total))), HOT))
				.append(Component.text(String.format(Locale.ROOT, " %3.0f%% ", capture.share(event.total)), NamedTextColor.GRAY))
				.append(Component.text(event.name, NamedTextColor.WHITE))
				.append(Component.text(String.format(Locale.ROOT, "  %,d× (%s per tick), slowest %s", event.count,
					number(capture.ticks == 0 ? event.count : (double) event.count / capture.ticks), ms(event.max)), NamedTextColor.GRAY)));
		}
	}

	private static void variables(CommandSender sender, int count) {
		Capture capture = require(sender);
		if (capture == null)
			return;
		List<VariableStats> variables = HyperTraceReport.sortedVariables(capture);
		if (variables.isEmpty()) {
			sender.sendMessage(Component.text(" No global variables were written yet.", NamedTextColor.GRAY));
			return;
		}
		double seconds = Math.max(1, capture.elapsedNanos() / 1e9);
		sender.sendMessage(Component.text(String.format(Locale.ROOT, " Global variable writes: %,d created, %,d updated, %,d deleted",
			capture.variableCreates, capture.variableUpdates, capture.variableDeletes), NamedTextColor.WHITE));
		for (int i = 0; i < Math.min(count, variables.size()); i++) {
			VariableStats variable = variables.get(i);
			LineStats writer = variable.topWriter();
			Component hover = Component.text("{" + variable.group + "}", ACCENT)
				.append(Component.newline()).append(detail("Saved to disk", variable.memoryOnly() ? "no ({-memory} variable)" : "yes"))
				.append(Component.newline()).append(detail("Created / updated / deleted", String.format(Locale.ROOT, "%,d / %,d / %,d", variable.creates, variable.updates, variable.deletes)))
				.append(Component.newline()).append(detail("Different names", String.format(Locale.ROOT, "%,d", variable.names.size())))
				.append(variable.lastType == null ? Component.empty() : Component.newline().append(detail("Last value type", variable.lastType)))
				.append(writer == null ? Component.empty() : Component.newline().append(detail("Written most by", writer.location() + "  " + shorten(writer.code, 80))));
			sender.sendMessage(rank(i)
				.append(Component.text(String.format(Locale.ROOT, "%,8.1f/s ", variable.writes() / seconds), HOT))
				.append(Component.text("{" + variable.group + "}", NamedTextColor.WHITE))
				.append(Component.text(variable.memoryOnly() ? "  memory only" : "", NamedTextColor.DARK_GRAY))
				.append(Component.text(writer == null ? "" : "  " + writer.location(), ACCENT))
				.hoverEvent(HoverEvent.showText(hover)));
		}
	}

	private static void loops(CommandSender sender) {
		Capture capture = HyperTrace.current();
		if (capture == null) {
			sender.sendMessage(Component.text("Loops are watched during a capture. Use /hypertrace start.", NamedTextColor.RED));
			return;
		}
		List<Component> rows = new ArrayList<>();
		for (LineStats line : capture.loops) {
			LoopSection loop = line.loopRef == null ? null : line.loopRef.get();
			if (loop == null)
				continue;
			List<Long> counters = loop.getRunningLoopCounters();
			if (counters.isEmpty())
				continue;
			StringBuilder iterations = new StringBuilder();
			for (int i = 0; i < Math.min(counters.size(), 8); i++)
				iterations.append(i == 0 ? "" : ", ").append(counters.get(i));
			if (counters.size() > 8)
				iterations.append(", …");
			rows.add(Component.text(" " + line.location(), ACCENT)
				.append(Component.text("  " + shorten(line.code.strip(), 50), NamedTextColor.WHITE))
				.append(Component.text(String.format(Locale.ROOT, "  %d running, at iteration %s (%,.0f/s)", counters.size(), iterations,
					line.iterationsPerSecond), NamedTextColor.GRAY)));
		}
		sender.sendMessage(title("HyperTrace", rows.isEmpty() ? "no loops are running right now" : "loops running right now"));
		rows.forEach(sender::sendMessage);
	}

	private static void report(CommandSender sender) {
		if (HyperTrace.isRolling()) {
			clip(sender);
			return;
		}
		Capture capture = require(sender);
		if (capture != null)
			sendReportLocation(sender, HyperTrace.writeReport(capture, true));
	}

	private static void clip(CommandSender sender) {
		if (!HyperTrace.isRolling()) {
			sender.sendMessage(Component.text("Clips need the rolling buffer: /hypertrace rolling on. For a normal capture use /hypertrace report.", NamedTextColor.RED));
			return;
		}
		File report = HyperTrace.clip("clip", true);
		Capture clip = HyperTrace.lastClip();
		if (clip != null)
			sender.sendMessage(title("HyperTrace", String.format(Locale.ROOT, "clip of the last %.0f s saved", clip.elapsedNanos() / 1e9)));
		sendReportLocation(sender, report);
	}

	private static void rolling(CommandSender sender, Settings settings, String[] args) {
		if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
			sender.sendMessage(title("HyperTrace", "rolling buffer is " + (HyperTrace.isRolling() ? "on" : "off")
				+ String.format(Locale.ROOT, " (window %d s, lag spike clips at %d ms)", settings.windowSeconds(), settings.spikeMs())));
			sender.sendMessage(Component.text(" /hypertrace rolling on|off", ACCENT));
			return;
		}
		boolean on = args[1].equalsIgnoreCase("on");
		if (on) {
			if (HyperTrace.isRolling()) {
				sender.sendMessage(Component.text("The rolling buffer is already on.", NamedTextColor.GRAY));
				return;
			}
			if (HyperTrace.current() != null) {
				sender.sendMessage(Component.text("Stop the running capture first (/hypertrace stop).", NamedTextColor.RED));
				return;
			}
			HyperTrace.startRolling(settings);
			HyperTrace.saveRollingSetting(true);
			sender.sendMessage(title("HyperTrace", "rolling buffer on (stays on after restarts)"));
			sender.sendMessage(Component.text(String.format(Locale.ROOT,
				" Everything is measured all the time; ticks over %d ms save a clip by itself. /hypertrace clip saves one now.",
				settings.spikeMs()), NamedTextColor.GRAY));
		} else {
			if (HyperTrace.isRolling())
				HyperTrace.stop(true);
			HyperTrace.saveRollingSetting(false);
			sender.sendMessage(title("HyperTrace", "rolling buffer off"));
		}
	}

	private static void reset(CommandSender sender) {
		if (HyperTrace.current() == null) {
			sender.sendMessage(Component.text("No capture is running.", NamedTextColor.RED));
			return;
		}
		HyperTrace.reset();
		sender.sendMessage(title("HyperTrace", "capture restarted, all numbers cleared"));
	}

	private static void sendReportLocation(CommandSender sender, @Nullable File report) {
		if (report == null)
			return;
		String path = "plugins/" + Skript.getInstance().getDataFolder().getName() + "/hypertrace/" + report.getName();
		sender.sendMessage(Component.text(" Full report: ", NamedTextColor.GRAY)
			.append(Component.text(path, ACCENT).clickEvent(ClickEvent.copyToClipboard(path))
				.hoverEvent(HoverEvent.showText(Component.text("Click to copy. Open the file in a browser.")))));
	}

	private static Component rank(int index) {
		return Component.text(String.format(Locale.ROOT, " %d. ", index + 1), NamedTextColor.DARK_GRAY);
	}

	private static TextComponent title(String name, String text) {
		return Component.text(name, ACCENT).append(Component.text(" » " + text, NamedTextColor.WHITE));
	}

	private static Component detail(String name, String value) {
		return Component.text(name + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
	}

	private static int parseInt(String text, int fallback) {
		try {
			return Math.max(1, Math.min(50, Integer.parseInt(text)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static long parseDuration(String text) {
		String value = text.toLowerCase(Locale.ENGLISH).strip();
		long unit = 1;
		if (value.endsWith("h")) {
			unit = 3600;
		} else if (value.endsWith("m")) {
			unit = 60;
		} else if (!value.endsWith("s")) {
			value += "s";
		}
		try {
			return Long.parseLong(value.substring(0, value.length() - 1)) * unit;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String duration(long seconds) {
		if (seconds % 3600 == 0)
			return seconds / 3600 + " h";
		if (seconds % 60 == 0)
			return seconds / 60 + " min";
		return seconds + " s";
	}

}
