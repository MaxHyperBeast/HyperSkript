package org.skriptlang.skript.util;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
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

}
