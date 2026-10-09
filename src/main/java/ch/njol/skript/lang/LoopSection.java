package ch.njol.skript.lang;

import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Represents a loop section.
 *
 * @see ch.njol.skript.sections.SecWhile
 * @see ch.njol.skript.sections.SecLoop
 */
public abstract class LoopSection extends Section implements SyntaxElement, Debuggable, SectionExitHandler {

	protected final transient Map<Event, Long> currentLoopCounter = createLoopCounterMap();

	/**
	 * Creates the map behind {@link #currentLoopCounter}. Loops that keep their counters elsewhere return a view
	 * (see {@link #loopCounterView(Supplier, ToLongFunction)}), so code that reads the field still sees the counters.
	 */
	protected Map<Event, Long> createLoopCounterMap() {
		return new WeakHashMap<>();
	}

	/**
	 * A read-only live view of loop counters kept in per-event state objects. Some addons read
	 * {@link #currentLoopCounter} through reflection (e.g. SkTrace's loop watcher, from another thread).
	 * Removing is ignored, as the states are removed by the loop itself.
	 * @param states Supplies the map of states by event (it may not exist yet while the loop is constructed).
	 * @param counter Gets the counter of a state.
	 */
	@ApiStatus.Internal
	protected static <S> Map<Event, Long> loopCounterView(Supplier<Map<Event, S>> states, ToLongFunction<S> counter) {
		return new AbstractMap<>() {

			@Override
			public @Nullable Long get(Object key) {
				//noinspection SuspiciousMethodCalls
				S state = states.get().get(key);
				return state == null ? null : counter.applyAsLong(state);
			}

			@Override
			public boolean containsKey(Object key) {
				//noinspection SuspiciousMethodCalls
				return states.get().containsKey(key);
			}

			@Override
			public @Nullable Long remove(Object key) {
				return null;
			}

			@Override
			public Set<Entry<Event, Long>> entrySet() {
				// a snapshot, as the states are changed by the loop while this may be read from another thread
				for (int attempt = 0; attempt < 3; attempt++) {
					try {
						Set<Entry<Event, Long>> entries = new HashSet<>();
						for (Entry<Event, S> entry : new ArrayList<>(states.get().entrySet())) {
							if (entry.getKey() != null)
								entries.add(new SimpleImmutableEntry<>(entry.getKey(), counter.applyAsLong(entry.getValue())));
						}
						return entries;
					} catch (ConcurrentModificationException ignored) { }
				}
				return Set.of();
			}

			@Override
			public List<Long> values() {
				List<Long> values = new ArrayList<>();
				for (Entry<Event, Long> entry : entrySet())
					values.add(entry.getValue());
				return values;
			}

		};
	}

	/**
	 * @param event The event where the loop is used to return its loop iterations
	 * @return The loop iteration number
	 */
	public long getLoopCounter(Event event) {
		return currentLoopCounter.getOrDefault(event, 1L);
	}

	/**
	 * @return The next {@link TriggerItem} after the loop
	 */
	public abstract TriggerItem getActualNext();

	/**
	 * Exit the loop, used to reset the loop properties such as iterations counter
	 * @param event The event where the loop is used to reset its relevant properties
	 */
	@Override
	public void exit(Event event) {
		currentLoopCounter.remove(event);
	}

}
