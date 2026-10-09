package org.skriptlang.skript.util;

import ch.njol.skript.registrations.Classes;
import org.jetbrains.annotations.ApiStatus;
import org.skriptlang.skript.lang.arithmetic.Arithmetics;
import org.skriptlang.skript.lang.comparator.Comparators;
import org.skriptlang.skript.lang.converter.Converters;

/**
 * Clears every cache derived from Skript's registries (types, converters, comparators, operations).
 * <p>
 * These caches assume the registries don't change once Skript has loaded, but some addons register types and
 * converters at runtime (oopsk does for struct templates, by switching registrations back on through reflection).
 * Every registration therefore clears the caches, so lookups give the same results as without them.
 */
@ApiStatus.Internal
public final class RegistryCaches {

	private RegistryCaches() { }

	public static void clearAll() {
		Classes.clearCaches();
		Converters.clearCache();
		Comparators.clearCache();
		Arithmetics.clearCaches();
	}

}
