package org.skriptlang.skript.hypertrace;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.Trigger;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.hypertrace.Capture.LineStats;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;

/**
 * HyperTrace, HyperSkript's built-in script profiler.
 * <p>
 * Nothing is measured unless a capture runs ({@code /hypertrace start}). Without one, the hooks in Skript only read
 * {@link #active}. During a capture, everything that runs on the server thread is timed: event dispatches, event
 * filters, triggers, functions and (with line timing) every line of every script, plus tick times and saved
 * variable writes.
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
	private static @Nullable Capture lastCapture;
	private static @Nullable File lastReport;
	private static @Nullable Settings settings;
	private static @Nullable TickListener tickListener;
	private static @Nullable BukkitTask autoStop;
	private static @Nullable Thread watchdog;

	private HyperTrace() { }

	record Settings(boolean enabled, boolean lineTiming, int maxCaptureMinutes, int hangSeconds, int chatLines) { }

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
	 */
	public static void globalWrite(String name, boolean delete) {
		Capture current = captureForThread();
		if (current != null)
			current.globalWrite(name, delete);
	}

	// --- lifecycle ---

	/**
	 * Loads the settings and registers the command. Called when Skript enables.
	 */
	public static void enable(Skript plugin) {
		loadSettings(plugin);
		PluginCommand command = plugin.getCommand("hypertrace");
		if (command != null) {
			HyperTraceCommand executor = new HyperTraceCommand();
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}
	}

	/**
	 * Stops a running capture and saves its report. Called when Skript disables.
	 */
	public static void disable() {
		if (capture != null) {
			try {
				File report = stop(false);
				if (report != null)
					Skript.info("HyperTrace capture stopped, report saved to " + report.getPath());
			} catch (RuntimeException e) {
				Skript.getInstance().getLogger().log(Level.WARNING, "Could not save the HyperTrace report", e);
			}
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
			Math.max(1, config.getInt("chat-lines", 8)));
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
	 * @return The running capture, or else the last finished one.
	 */
	static @Nullable Capture currentOrLast() {
		Capture current = capture;
		return current != null ? current : lastCapture;
	}

	static @Nullable File lastReport() {
		return lastReport;
	}

	/**
	 * Starts a capture on the server thread.
	 * @param seconds Stop automatically after this many seconds (0 for the configured maximum).
	 */
	static Capture start(Settings settings, long seconds) {
		if (!Bukkit.isPrimaryThread())
			throw new IllegalStateException("HyperTrace captures must be started on the server thread");
		if (capture != null)
			throw new IllegalStateException("A capture is already running");
		Skript plugin = Skript.getInstance();
		Capture started = new Capture(Thread.currentThread(), settings.lineTiming());
		TickListener listener = new TickListener(started);
		Bukkit.getPluginManager().registerEvents(listener, plugin);
		tickListener = listener;
		capture = started;
		active = true;

		long limit = seconds > 0 ? seconds : settings.maxCaptureMinutes() * 60L;
		if (limit > 0) {
			autoStop = Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if (capture != started)
					return;
				File report = stop(true);
				HyperTraceCommand.announceAutoStop(started, report);
			}, limit * 20);
		}
		if (settings.hangSeconds() > 0 && settings.lineTiming())
			startWatchdog(started, settings.hangSeconds());
		return started;
	}

	/**
	 * Stops the running capture.
	 * @param async Whether to write the report off the server thread.
	 * @return The report file, or null if no capture was running or it couldn't be written.
	 */
	static @Nullable File stop(boolean async) {
		Capture stopped = halt();
		return stopped == null ? null : writeReport(stopped, async);
	}

	private static @Nullable Capture halt() {
		Capture stopped = capture;
		if (stopped == null)
			return null;
		active = false;
		capture = null;
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
		if (watchdog != null) {
			watchdog.interrupt();
			watchdog = null;
		}
		lastCapture = stopped;
		return stopped;
	}

	static void reset() {
		Capture running = capture;
		if (running == null)
			return;
		halt();
		lastCapture = null;
		start(settings(), 0);
	}

	/**
	 * Writes the HTML report of a capture to {@code plugins/Skript/hypertrace/}.
	 * The page is built on the calling thread (the server thread, which owns the data) and written to disk
	 * asynchronously if requested.
	 */
	static @Nullable File writeReport(Capture data, boolean async) {
		String html = new HyperTraceReport(data).html();
		File folder = new File(Skript.getInstance().getDataFolder(), "hypertrace");
		String name = "hypertrace-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".html";
		File file = new File(folder, name);
		Runnable write = () -> {
			try {
				Files.createDirectories(folder.toPath());
				Files.writeString(file.toPath(), html, StandardCharsets.UTF_8);
			} catch (IOException e) {
				Skript.getInstance().getLogger().log(Level.WARNING, "Could not write the HyperTrace report " + file, e);
			}
		};
		if (async && Skript.getInstance().isEnabled()) {
			Bukkit.getScheduler().runTaskAsynchronously(Skript.getInstance(), write);
		} else {
			write.run();
		}
		lastReport = file;
		return file;
	}

	private static void startWatchdog(Capture watched, int hangSeconds) {
		Thread thread = new Thread(() -> {
			long reportedFor = 0;
			while (capture == watched) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
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

	private static final class TickListener implements Listener {

		private final Capture data;

		TickListener(Capture data) {
			this.data = data;
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onTickStart(ServerTickStartEvent event) {
			data.tickStart();
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onTickEnd(ServerTickEndEvent event) {
			data.tickEnd(event.getTickDuration());
		}

	}

}
