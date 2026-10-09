package ch.njol.skript.bukkitutil;

import ch.njol.skript.localization.Message;
import ch.njol.skript.util.ValidationResult;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.log.runtime.RuntimeErrorProducer;

import java.util.Locale;

/**
 * Utility class for {@link NamespacedKey}
 */
public class NamespacedUtils {

	public static final Message NAMEDSPACED_FORMAT_MESSAGE = new Message("misc.namespacedutils.format");

	/**
	 * Check if {@code character} is a valid {@link Character} for the namespace section of a {@link NamespacedKey}.
	 * @param character The {@link Character} to check.
	 * @return {@code True} if valid, otherwise {@code false}.
	 */
	public static boolean isValidNamespaceChar(char character) {
		return (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') || character == '.' || character == '_' || character == '-';
	}

	/**
	 * Check if {@code character} is a valid {@link Character} for the key section of a {@link NamespacedKey}.
	 * @param character The {@link Character} to check.
	 * @return {@code True} if valid, otherwise {@code false}.
	 */
	public static boolean isValidKeyChar(char character) {
		return isValidNamespaceChar(character) || character == '/';
	}

	/**
	 * Check if the {@code string} is valid for a {@link NamespacedKey} and get a {@link ValidationResult}
	 * containing if it's valid, an error or warning message and the resulting {@link NamespacedKey}.
	 * @param string The {@link String} to check.
	 * @return {@link ValidationResult}.
	 */
	public static ValidationResult<NamespacedKey> checkValidation(String string) {
		if (string.length() > Short.MAX_VALUE)
			return new ValidationResult<>(false, "A namespaced key can not be longer than " + Short.MAX_VALUE + " characters.");
		String[] split = string.split(":");
		if (split.length > 2)
			return new ValidationResult<>(false, "A namespaced key can not have more than one ':'.");

		String key = split.length == 2 ? split[1] : split[0];
		if (key.isEmpty())
			return new ValidationResult<>(false, "The key cannot be empty.");
		for (char character : key.toCharArray()) {
			if (!isValidKeyChar(character)) {
				return new ValidationResult<>(false, "Invalid character '" + character + "'.");
			}
		}

		NamespacedKey namespacedKey;
		boolean emptyNamespace = false;
		if (split.length == 2) {
			String namespace = split[0];
			if (!namespace.isEmpty()) {
				for (char character : namespace.toCharArray()) {
					if (!isValidNamespaceChar(character)) {
						return new ValidationResult<>(false, "Invalid character '" + character + "'.");
					}
				}
				namespacedKey = new NamespacedKey(namespace, key);
			} else {
				emptyNamespace = true;
				namespacedKey = NamespacedKey.minecraft(key);
			}
		} else {
			namespacedKey = NamespacedKey.minecraft(key);
		}

		if (emptyNamespace) {
			return new ValidationResult<>(
				true,
				"The namespace section of the key is empty. Consider removing the ':'.",
				namespacedKey);
		}
		return new ValidationResult<>(true, namespacedKey);
	}

	/**
	 * A helper method to run {@link #checkValidation} and send the appropriate runtime errors/warnings.
	 * @param string The string to parse as a namespaced key.
	 * @param producer The producer from which to send runtime errors.
	 * @return The key, if parsed without errors, otherwise null.
	 */
	public static @Nullable NamespacedKey checkValidationAndSend(String string, RuntimeErrorProducer producer) {
		ValidationResult<NamespacedKey> validationResult = NamespacedUtils.checkValidation(string);
		String validationMessage = validationResult.message();
		if (!validationResult.valid()) {
			producer.error(validationMessage + ". " + NamespacedUtils.NAMEDSPACED_FORMAT_MESSAGE);
			return null;
		} else if (validationMessage != null) {
			producer.warning(validationMessage);
		}
		return validationResult.data();
	}

	/**
	 * Remembers the key parsed for the last string, for syntax that usually gets the same key every time
	 * (e.g. persistent data tags). Gives the same result as {@link #checkValidationAndSend(String, RuntimeErrorProducer)};
	 * only keys that were valid without any warning are remembered, so errors and warnings are still sent every time.
	 */
	@ApiStatus.Internal
	public static final class LastKey {

		private record Entry(String input, NamespacedKey key) { }

		private final boolean lowercase;
		private volatile @Nullable Entry last;

		/**
		 * @param lowercase Whether the input is lowercased (with {@link Locale#ENGLISH}) before parsing.
		 */
		public LastKey(boolean lowercase) {
			this.lowercase = lowercase;
		}

		public @Nullable NamespacedKey get(String input, RuntimeErrorProducer producer) {
			Entry entry = last;
			if (entry != null && entry.input.equals(input))
				return entry.key;
			String string = lowercase ? input.toLowerCase(Locale.ENGLISH) : input;
			ValidationResult<NamespacedKey> validationResult = checkValidation(string);
			String validationMessage = validationResult.message();
			if (!validationResult.valid()) {
				producer.error(validationMessage + ". " + NAMEDSPACED_FORMAT_MESSAGE);
				return null;
			} else if (validationMessage != null) {
				producer.warning(validationMessage);
				return validationResult.data();
			}
			NamespacedKey key = validationResult.data();
			if (key != null)
				last = new Entry(input, key);
			return key;
		}

	}

	/**
	 * Check if {@code string} is valid for a {@link NamespacedKey}.
	 * @param string The {@link String} to check.
	 * @return {@code True} if valid, otherwise {@code false}.
	 */
	public static boolean isValid(String string) {
		return checkValidation(string).valid();
	}

}
