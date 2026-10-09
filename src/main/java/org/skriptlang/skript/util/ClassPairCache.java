package org.skriptlang.skript.util;

import ch.njol.util.Pair;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * A lock-free cache keyed by a pair of classes, which also caches {@code null} results.
 * Lookups don't allocate, unlike a map keyed by a pair object.
 * <p>
 * Values are computed outside any lock, so a computation may recursively query the same cache.
 * If two threads compute the same key at once, the first stored value wins.
 *
 * @param <V> The value type.
 */
@ApiStatus.Internal
public final class ClassPairCache<V> {

	private static final Object NULL = new Object();

	private final Map<Class<?>, Map<Class<?>, Object>> cache = new ConcurrentHashMap<>();

	/**
	 * Gets the cached value for the pair, computing and caching it if absent.
	 * @param first The first class of the pair.
	 * @param second The second class of the pair.
	 * @param computer Computes the value if it isn't cached yet. May return null, which is cached as well.
	 * @return The cached or computed value.
	 */
	@SuppressWarnings("unchecked")
	public <F, S> @Nullable V get(Class<F> first, Class<S> second, BiFunction<Class<F>, Class<S>, ? extends V> computer) {
		Map<Class<?>, Object> inner = cache.get(first);
		if (inner == null) {
			Map<Class<?>, Object> created = new ConcurrentHashMap<>();
			inner = cache.putIfAbsent(first, created);
			if (inner == null)
				inner = created;
		}
		Object value = inner.get(second);
		if (value == null) {
			V computed = computer.apply(first, second);
			value = inner.putIfAbsent(second, computed == null ? NULL : computed);
			if (value == null)
				return computed;
		}
		return value == NULL ? null : (V) value;
	}

	/**
	 * Removes the cached value of a pair, so it's computed again on the next lookup.
	 */
	public void remove(Class<?> first, Class<?> second) {
		Map<Class<?>, Object> inner = cache.get(first);
		if (inner != null)
			inner.remove(second);
	}

	/**
	 * Removes all cached values.
	 */
	public void clear() {
		cache.clear();
	}

	/**
	 * A view of this cache as a map keyed by {@code Pair<Class<?>, Class<?>>}, the layout these caches had before.
	 * Some addons change Skript's caches through reflection (e.g. oopsk removes entries when it registers types at
	 * runtime), so the cache fields keep that map type. Changes made through the view apply to this cache.
	 */
	public Map<Pair<Class<?>, Class<?>>, V> asPairMap() {
		return new PairMapView();
	}

	private final class PairMapView extends AbstractMap<Pair<Class<?>, Class<?>>, V> {

		@Override
		@SuppressWarnings("unchecked")
		public @Nullable V get(Object key) {
			if (!(key instanceof Pair<?, ?> pair) || !(pair.getFirst() instanceof Class<?> first))
				return null;
			Map<Class<?>, Object> inner = cache.get(first);
			Object value = inner == null ? null : inner.get(pair.getSecond());
			return value == null || value == NULL ? null : (V) value;
		}

		@Override
		public boolean containsKey(Object key) {
			if (!(key instanceof Pair<?, ?> pair) || !(pair.getFirst() instanceof Class<?> first))
				return false;
			Map<Class<?>, Object> inner = cache.get(first);
			return inner != null && pair.getSecond() != null && inner.containsKey(pair.getSecond());
		}

		@Override
		public @Nullable V put(Pair<Class<?>, Class<?>> key, V value) {
			V previous = get(key);
			cache.computeIfAbsent(key.getFirst(), k -> new ConcurrentHashMap<>())
				.put(key.getSecond(), value == null ? NULL : value);
			return previous;
		}

		@Override
		public @Nullable V remove(Object key) {
			V previous = get(key);
			if (key instanceof Pair<?, ?> pair && pair.getFirst() instanceof Class<?> first
					&& pair.getSecond() instanceof Class<?> second)
				ClassPairCache.this.remove(first, second);
			return previous;
		}

		@Override
		public void clear() {
			cache.clear();
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<Entry<Pair<Class<?>, Class<?>>, V>> entrySet() {
			// a snapshot, only used by code that iterates the old map
			Set<Entry<Pair<Class<?>, Class<?>>, V>> entries = new HashSet<>();
			cache.forEach((first, inner) -> inner.forEach((second, value) ->
				entries.add(new SimpleImmutableEntry<>(new Pair<>(first, second), value == NULL ? null : (V) value))));
			return entries;
		}

	}

}
