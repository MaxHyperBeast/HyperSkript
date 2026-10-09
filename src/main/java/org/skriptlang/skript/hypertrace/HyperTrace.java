package org.skriptlang.skript.hypertrace;

import ch.njol.skript.Skript;
import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.Trigger;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.hypertrace.Capture.LineStats;
import org.skriptlang.skript.hypertrace.Capture.TriggerKind;
import org.skriptlang.skript.hypertrace.Capture.TriggerStats;
import org.skriptlang.skript.lang.script.Script;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HyperTrace, HyperSkript's built-in script profiler.
 * <p>
 * Nothing is measured unless a capture runs ({@code /hypertrace start}) or the rolling buffer is on. Without one, the
 * hooks in Skript only read {@link #active}. During a capture, everything that runs on the server thread is timed:
 * event dispatches, event filters, triggers, functions and (with line timing) every line of every script, plus tick
 * times, global variable writes and variable file saves.
 * <p>
 * The rolling buffer is a capture that runs all the time in segments of {@code window-seconds}; a clip
 * ({@code /hypertrace clip}, or automatically after a lag spike) reports the last one or two segments.
 */
@ApiStatus.Internal
public final class HyperTrace {

	/**
	 * Whether a capture is running. The only thing Skript's hot paths read when HyperTrace isn't used.
	 * Deliberately not volatile: other threads may see a change late, which only means they check
	 * {@link #captureForThread()} (which is) a little longer or start counting a little later.
	 */
	public static boolean active;

	private static volatile @Nullable Capture capture;
	/** The previous segment of the rolling buffer. */
	private static @Nullable Capture previousSegment;
	private static boolean rolling;
	private static @Nullable Capture lastCapture;
	private static @Nullable Capture lastClip;
	private static @Nullable Settings settings;
	private static @Nullable TickListener tickListener;
	private static @Nullable BukkitTask autoStop;
	private static @Nullable BukkitTask indicator;
	private static @Nullable Thread watchdog;
	private static long nextSpikeNanos;
	private static boolean spikePending;
	private static @Nullable String reportTemplate;

	/** Every trigger created, to find the ones that never ran. */
	private static final Set<Trigger> TRIGGERS = Collections.newSetFromMap(new WeakHashMap<>());

	private HyperTrace() { }

	record Settings(boolean enabled, boolean lineTiming, int maxCaptureMinutes, int hangSeconds, int chatLines,
					boolean includeSources, boolean writeJson, boolean indicator,
					boolean rollingEnabled, int windowSeconds, int spikeMs, int spikeCooldownSeconds, int spikeAfterSeconds) { }

	// --- hooks (called by Skript) ---

	/**
	 * @return The running capture if the current thread is the one it records, otherwise null.
	 */
	public static @Nullable Capture captureForThread() {
		Capture current = capture;
		return current != null && current.thread == Thread.currentThread() ? current : null;
	}

	/**
	 * Counts a script run on another thread, which isn't timed.
	 */
	public static void untracedRun() {
		Capture current = capture;
		if (current != null)
			current.otherThreadRuns.incrementAndGet();
	}

	/**
	 * {@link SkriptEvent#check(Event)}, timed when the current thread is captured.
	 */
	public static boolean checkEvent(Trigger trigger, SkriptEvent skriptEvent, Event event) {
		Capture current = captureForThread();
		return current == null ? skriptEvent.check(event) : current.checkEvent(trigger, skriptEvent, event);
	}

	/**
	 * Records a write to a global variable.
	 * @param value The new value, or null if the variable is deleted.
	 */
	public static void globalWrite(String name, @Nullable Object value) {
		Capture current = captureForThread();
		if (current != null)
			current.globalWrite(name, value);
	}

	/**
	 * Records a full save of the variables file. Called from the thread that saved it.
	 */
	public static void variablesSaved(long startNanos, long endNanos, long bytes, int changes) {
		Capture current = capture;
		if (current != null)
			current.variablesSaved(startNanos, endNanos, bytes, changes);
	}

	/**
	 * Remembers a trigger, so a report can list the triggers that never ran. Called when a trigger is created.
	 */
	public static void registerTrigger(Trigger trigger) {
		synchronized (TRIGGERS) {
			TRIGGERS.add(trigger);
		}
	}

	// --- lifecycle ---

	/**
	 * Loads the settings and registers the command. Called when Skript enables.
	 */
	public static void enable(Skript plugin) {
		Settings loaded = loadSettings(plugin);
		PluginCommand command = plugin.getCommand("hypertrace");
		if (command != null) {
			HyperTraceCommand executor = new HyperTraceCommand();
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}
		if (loaded.enabled() && loaded.rollingEnabled()) {
			// on the first tick, once everything is loaded
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (capture == null)
					startRolling(settings());
			});
		}
	}

	/**
	 * Stops a running capture and saves its report. Called when Skript disables.
	 */
	public static void disable() {
		if (capture == null)
			return;
		try {
			if (rolling) {
				halt();
			} else {
				File report = stop(false);
				if (report != null)
					Skript.info("HyperTrace capture stopped, report saved to " + report.getPath());
			}
		} catch (RuntimeException e) {
			Skript.getInstance().getLogger().log(Level.WARNING, "Could not save the HyperTrace report", e);
		}
	}

	static Settings loadSettings(Skript plugin) {
		File file = new File(plugin.getDataFolder(), "hypertrace.yml");
		if (!file.exists()) {
			try {
				plugin.saveResource("hypertrace.yml", false);
			} catch (IllegalArgumentException e) {
				// not in the jar (e.g. in tests): use the defaults
			}
		}
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		Settings loaded = new Settings(
			config.getBoolean("enabled", true),
			config.getBoolean("line-timing", true),
			Math.max(0, config.getInt("max-capture-minutes", 30)),
			Math.max(0, config.getInt("hang-detection-seconds", 10)),
			Math.max(1, config.getInt("chat-lines", 8)),
			config.getBoolean("report.include-sources", true),
			config.getBoolean("report.write-json", true),
			config.getBoolean("indicator", true),
			config.getBoolean("rolling.enabled", false),
			Math.max(10, config.getInt("rolling.window-seconds", 60)),
			Math.max(0, config.getInt("rolling.spike-ms", 250)),
			Math.max(10, config.getInt("rolling.spike-cooldown-seconds", 300)),
			Math.max(0, config.getInt("rolling.spike-after-seconds", 5)));
		settings = loaded;
		return loaded;
	}

	static Settings settings() {
		Settings current = settings;
		return current != null ? current : loadSettings(Skript.getInstance());
	}

	static @Nullable Capture current() {
		return capture;
	}

	/**
	 * @return The last clip of the rolling buffer.
	 */
	static @Nullable Capture lastClip() {
		return lastClip;
	}

	static boolean isRolling() {
		return rolling;
	}

	/**
	 * @return The running capture, or else the last finished one.
	 */
	static @Nullable Capture currentOrLast() {
		Capture current = capture;
		return current != null ? current : lastCapture;
	}

	/**
	 * Starts a capture on the server thread.
	 * @param seconds Stop automatically after this many seconds (0 for the configured maximum).
	 * @param starter Who started it, for the action bar indicator.
	 */
	static Capture start(Settings settings, long seconds, @Nullable CommandSender starter) {
		Capture started = begin(settings, false);
		long limit = seconds > 0 ? seconds : settings.maxCaptureMinutes() * 60L;
		if (limit > 0) {
			autoStop = Bukkit.getScheduler().runTaskLater(Skript.getInstance(), () -> {
				if (capture != started)
					return;
				File report = stop(true);
				HyperTraceCommand.announceAutoStop(started, report);
			}, limit * 20);
		}
		if (settings.indicator() && starter instanceof Player player)
			startIndicator(player, limit);
		return started;
	}

	/**
	 * Starts the rolling buffer on the server thread.
	 */
	static Capture startRolling(Settings settings) {
		Capture started = begin(settings, true);
		nextSpikeNanos = System.nanoTime() + settings.windowSeconds() * 1_000_000_000L; // let the server settle first
		return started;
	}

	private static Capture begin(Settings settings, boolean rollingMode) {
		if (!Bukkit.isPrimaryThread())
			throw new IllegalStateException("HyperTrace captures must be started on the server thread");
		if (capture != null)
			throw new IllegalStateException("A capture is already running");
		Capture started = new Capture(Thread.currentThread(), settings.lineTiming());
		TickListener listener = new TickListener();
		Bukkit.getPluginManager().registerEvents(listener, Skript.getInstance());
		tickListener = listener;
		rolling = rollingMode;
		previousSegment = null;
		spikePending = false;
		capture = started;
		active = true;
		if (settings.hangSeconds() > 0 && settings.lineTiming())
			startWatchdog(settings.hangSeconds());
		return started;
	}

	/**
	 * Stops the running capture or rolling buffer.
	 * @param async Whether to write the report off the server thread.
	 * @return The report file, or null if no capture was running.
	 */
	static @Nullable File stop(boolean async) {
		boolean wasRolling = rolling;
		Capture previous = previousSegment;
		Capture stopped = halt();
		if (stopped == null)
			return null;
		if (wasRolling) {
			Capture clip = previous == null ? stopped : Capture.merge(List.of(previous, stopped), "clip");
			lastCapture = clip;
			return writeReport(clip, async);
		}
		return writeReport(stopped, async);
	}

	private static @Nullable Capture halt() {
		Capture stopped = capture;
		if (stopped == null)
			return null;
		active = false;
		capture = null;
		rolling = false;
		stopped.stoppedMillis = System.currentTimeMillis();
		stopped.stoppedNanos = System.nanoTime();
		if (tickListener != null) {
			HandlerList.unregisterAll(tickListener);
			tickListener = null;
		}
		if (autoStop != null) {
			autoStop.cancel();
			autoStop = null;
		}
		if (indicator != null) {
			indicator.cancel();
			indicator = null;
		}
		if (watchdog != null) {
			watchdog.interrupt();
			watchdog = null;
		}
		lastCapture = stopped;
		previousSegment = null;
		return stopped;
	}

	static void reset() {
		if (capture == null)
			return;
		boolean wasRolling = rolling;
		halt();
		lastCapture = null;
		if (wasRolling)
			startRolling(settings());
		else
			start(settings(), 0, null);
	}

	/**
	 * Saves a report of the rolling buffer's last one or two segments (between one and two windows).
	 * @return The report file, or null if the rolling buffer is off.
	 */
	static @Nullable File clip(String kind, boolean async) {
		Capture current = capture;
		if (current == null || !rolling)
			return null;
		Capture previous = previousSegment;
		Capture clip = Capture.merge(previous == null ? List.of(current) : List.of(previous, current), kind);
		lastCapture = clip;
		lastClip = clip;
		return writeReport(clip, async);
	}

	/**
	 * Starts a new segment of the rolling buffer once the current one is a window long.
	 */
	private static void rotateIfDue(Capture current) {
		if (current.elapsedNanos() < settings().windowSeconds() * 1_000_000_000L)
			return;
		current.stoppedMillis = System.currentTimeMillis();
		current.stoppedNanos = System.nanoTime();
		previousSegment = current;
		capture = new Capture(current.thread, current.lineTiming);
	}

	private static void checkSpike(double tickMs) {
		Settings current = settings();
		if (current.spikeMs() <= 0 || spikePending || tickMs < current.spikeMs() || System.nanoTime() < nextSpikeNanos)
			return;
		spikePending = true;
		nextSpikeNanos = System.nanoTime() + current.spikeCooldownSeconds() * 1_000_000_000L;
		String message = String.format(Locale.ROOT, "[HyperTrace] Lag spike: a tick took %.0f ms. Saving a clip in %d s.",
			tickMs, current.spikeAfterSeconds());
		Skript.getInstance().getLogger().info(message);
		Bukkit.getScheduler().runTaskLater(Skript.getInstance(), () -> {
			spikePending = false;
			File report = clip("spike", true);
			if (report != null)
				HyperTraceCommand.announceSpike(lastClip, report, tickMs);
		}, Math.max(1, current.spikeAfterSeconds() * 20L));
	}

	/**
	 * Turns the rolling buffer setting on or off in hypertrace.yml, keeping the file's comments.
	 */
	static void saveRollingSetting(boolean enabled) {
		File file = new File(Skript.getInstance().getDataFolder(), "hypertrace.yml");
		try {
			String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			Matcher matcher = Pattern.compile("(?m)^(rolling:\\s*\\n(?:[ \\t]*(?:#.*)?\\n)*?[ \\t]+enabled:[ \\t]*)(true|false)").matcher(text);
			if (matcher.find()) {
				text = text.substring(0, matcher.start(2)) + enabled + text.substring(matcher.end(2));
				Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			Skript.getInstance().getLogger().log(Level.WARNING, "Could not update hypertrace.yml", e);
		}
		loadSettings(Skript.getInstance());
	}

	// --- reports ---

	/**
	 * Writes the report of a capture to {@code plugins/Skript/hypertrace/}: an HTML page and (if enabled) its data as JSON.
	 * The data is collected on the calling thread (the server thread, which owns the capture) and written to disk
	 * asynchronously if requested.
	 */
	static File writeReport(Capture data, boolean async) {
		Settings current = settings();
		String json = new HyperTraceReport(data, silentTriggers(data), current.includeSources()).json();
		String html = template().replace("/*HYPERTRACE_DATA*/null", json.replace("</", "<\\/")
			.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"));
		File folder = new File(Skript.getInstance().getDataFolder(), "hypertrace");
		String base = "hypertrace-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
			+ (data.kind.equals("capture") ? "" : "-" + data.kind);
		File file = new File(folder, base + ".html");
		File jsonFile = new File(folder, base + ".json");
		boolean writeJson = current.writeJson();
		Runnable write = () -> {
			try {
				Files.createDirectories(folder.toPath());
				Files.writeString(file.toPath(), html, StandardCharsets.UTF_8);
				if (writeJson)
					Files.writeString(jsonFile.toPath(), json, StandardCharsets.UTF_8);
			} catch (IOException e) {
				Skript.getInstance().getLogger().log(Level.WARNING, "Could not write the HyperTrace report " + file, e);
			}
		};
		if (async && Skript.getInstance().isEnabled()) {
			Bukkit.getScheduler().runTaskAsynchronously(Skript.getInstance(), write);
		} else {
			write.run();
		}
		return file;
	}

	private static String template() {
		String template = reportTemplate;
		if (template != null)
			return template;
		try (InputStream in = Skript.getInstance().getResource("hypertrace/report.html")) {
			template = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			template = null;
		}
		if (template == null)
			template = "<!DOCTYPE html><html><body><p>HyperTrace report template missing.</p><script>window.HT=/*HYPERTRACE_DATA*/null;</script></body></html>";
		reportTemplate = template;
		return template;
	}

	/**
	 * A trigger that was loaded but never ran during a capture.
	 */
	record SilentTrigger(TriggerKind kind, String name, String script, int line, long checks) { }

	/**
	 * @return The loaded event, command and function triggers that didn't run, and the number of loaded triggers.
	 */
	static SilentTriggers silentTriggers(Capture data) {
		List<Trigger> all;
		synchronized (TRIGGERS) {
			all = new ArrayList<>(TRIGGERS);
		}
		Set<Script> loaded = ScriptLoader.getLoadedScripts();
		List<SilentTrigger> silent = new ArrayList<>();
		int total = 0;
		for (Trigger trigger : all) {
			Script script = trigger.getScript();
			TriggerKind kind = Capture.kindOf(trigger);
			if (script == null || kind == TriggerKind.OTHER || !loaded.contains(script))
				continue;
			total++;
			String scriptName = Capture.displayName(script);
			TriggerStats stats = data.triggers.get(Capture.triggerKey(scriptName, trigger.getLineNumber(), trigger.getName()));
			if (stats == null || stats.calls + stats.resumes == 0)
				silent.add(new SilentTrigger(kind, trigger.getName(), scriptName, trigger.getLineNumber(), stats == null ? 0 : stats.checks));
		}
		silent.sort((a, b) -> a.script.equals(b.script) ? Integer.compare(a.line, b.line) : a.script.compareTo(b.script));
		return new SilentTriggers(total, silent);
	}

	record SilentTriggers(int loaded, List<SilentTrigger> list) { }

	// --- indicator and watchdog ---

	private static void startIndicator(Player player, long limitSeconds) {
		UUID id = player.getUniqueId();
		WeakReference<Capture> watched = new WeakReference<>(capture);
		indicator = Bukkit.getScheduler().runTaskTimer(Skript.getInstance(), () -> {
			Capture current = capture;
			Player target = Bukkit.getPlayer(id);
			if (current == null || current != watched.get() || target == null)
				return;
			long seconds = current.elapsedNanos() / 1_000_000_000L;
			float worst = current.worstTicks.isEmpty() ? 0 : current.worstTicks.getFirst().ms;
			String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
				+ (limitSeconds > 0 ? String.format(Locale.ROOT, " / %d:%02d", limitSeconds / 60, limitSeconds % 60) : "");
			target.sendActionBar(Component.text("HyperTrace ", TextColor.color(0x6f9cf0))
				.append(Component.text("● ", NamedTextColor.RED))
				.append(Component.text(time + "  ·  Skript " + String.format(Locale.ROOT, "%.2f", current.perTickMs(current.tickSkriptTotalNanos))
					+ " ms/tick  ·  slowest tick " + String.format(Locale.ROOT, "%.1f", worst) + " ms", NamedTextColor.GRAY)));
		}, 20, 20);
	}

	private static void startWatchdog(int hangSeconds) {
		Thread thread = new Thread(() -> {
			long reportedFor = 0;
			while (active) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
				Capture watched = capture;
				if (watched == null)
					return;
				long progress = watched.lastProgressNanos;
				if (progress == reportedFor || System.nanoTime() - progress < hangSeconds * 1_000_000_000L)
					continue;
				reportedFor = progress;
				List<LineStats> running = watched.runningLines();
				StringBuilder message = new StringBuilder("[HyperTrace] The server hasn't finished a tick for ")
					.append(hangSeconds).append(" seconds.");
				if (running.isEmpty()) {
					message.append(" No script line is running right now, so the cause is outside of Skript (see the stack below).");
				} else {
					message.append(" Script lines running (outermost first):");
					for (LineStats line : running) {
						message.append("\n  ").append(line.location()).append("  ").append(line.code);
						for (LineStats section : line.ancestors)
							message.append("\n      inside ").append(section.location()).append("  ").append(section.code);
						if (line.trigger != null)
							message.append("\n      in ").append(line.trigger.label());
					}
				}
				StackTraceElement[] stack = watched.thread.getStackTrace();
				message.append("\n  Server thread:");
				for (int i = 0; i < Math.min(stack.length, 12); i++)
					message.append("\n    at ").append(stack[i]);
				String text = message.toString();
				Skript.getInstance().getLogger().warning(text);
				synchronized (watched.hangs) {
					watched.hangs.add(text);
				}
			}
		}, "HyperTrace hang watchdog");
		thread.setDaemon(true);
		thread.start();
		watchdog = thread;
	}

	/**
	 * Feeds tick times to the current capture, rotates the rolling buffer and watches for lag spikes.
	 */
	private static final class TickListener implements Listener {

		@EventHandler(priority = EventPriority.LOWEST)
		public void onTickStart(ServerTickStartEvent event) {
			Capture current = capture;
			if (current != null)
				current.tickStart();
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onTickEnd(ServerTickEndEvent event) {
			Capture current = capture;
			if (current == null)
				return;
			double tickMs = current.tickEnd(event.getTickDuration());
			if (rolling) {
				checkSpike(tickMs);
				rotateIfDue(current);
			}
		}

	}

}
