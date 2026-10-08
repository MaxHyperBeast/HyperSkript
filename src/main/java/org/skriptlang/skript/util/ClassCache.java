package org.skriptlang.skript.util;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A lock-free cache keyed by class, which also caches {@code null} results.
 * <p>
 * Used instead of {@link ClassValue}, whose lookups often take a slow path on a server with many class values.
 * Values are computed outside any lock, so a computation may query the same cache.
 * If two threads compute the same key at once, the first stored value wins.
 *
 * @param <V> The value type.
 */
@ApiStatus.Internal
public final class ClassCache<V> {

	private static final Object NULL = new Object();

	private final Map<Class<?>, Object> cache = new ConcurrentHashMap<>();
	private final Function<Class<?>, ? extends V> computer;

	/**
	 * @param computer Computes the value for a class that isn't cached yet. May return null, which is cached as well.
	 */
	public ClassCache(Function<Class<?>, ? extends V> computer) {
		this.computer = computer;
	}

	@SuppressWarnings("unchecked")
	public @Nullable V get(Class<?> type) {
		Object value = cache.get(type);
		if (value == null) {
			V computed = computer.apply(type);
			value = cache.putIfAbsent(type, computed == null ? NULL : computed);
			if (value == null)
				return computed;
		}
		return value == NULL ? null : (V) value;
	}

}
