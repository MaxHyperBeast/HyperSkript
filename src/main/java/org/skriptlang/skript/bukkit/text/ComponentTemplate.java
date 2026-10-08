package org.skriptlang.skript.bukkit.text;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.StringMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.VirtualComponent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Formats a string with expressions (e.g. {@code "<red>Hello %player%!"}) into a component
 * without parsing the whole string every time.
 * <p>
 * The static parts are parsed once, with a placeholder character in place of each expression.
 * When formatting, the values are put into the placeholders of the parsed component.
 * This gives the same component as parsing the complete string, as long as the values can't contain formatting
 * and the placeholders are plain text in the parsed template. Whenever that isn't certain, the complete string
 * is parsed like before:
 * <ul>
 *     <li>values that are empty or contain {@code < > & § \} or placeholder characters,</li>
 *     <li>an expression inside a tag, or directly after {@code & § \} (it could form a legacy code),</li>
 *     <li>tags whose output depends on the text length or are unknown (gradients, rainbows, addon tags, ...),</li>
 *     <li>link parsing, or a placeholder that doesn't end up exactly once in the text of the parsed template.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class ComponentTemplate {

	/**
	 * Placeholders are taken from the Unicode private use area.
	 */
	private static final char FIRST_PLACEHOLDER = (char) 0xE000;
	private static final int MAX_PLACEHOLDERS = 0x1000;

	/**
	 * Tags whose output does not depend on the text they apply to (after {@link TextComponentParser#reformatText(String)}).
	 */
	private static final Set<String> POSITION_INDEPENDENT_TAGS = Set.of(
		// colors
		"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey",
		"dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
		"color", "colour", "c",
		// Skript's color names
		"dark_cyan", "dark_turquoise", "cyan", "purple", "dark_yellow", "orange", "light_grey", "light_gray", "silver",
		"dark_silver", "light_blue", "indigo", "light_green", "lime_green", "lime", "light_cyan", "light_aqua",
		"turquoise", "light_red", "pink", "magenta", "light_yellow", "brown",
		// decorations ('u' is Skript's unicode tag, so it's not included)
		"bold", "b", "italic", "em", "i", "underlined", "strikethrough", "st", "obfuscated", "obf",
		"magic", "strike", "s", "underline", "italics",
		// others
		"reset", "r", "newline", "br", "shadow",
		"click", "hover", "insert", "insertion", "ins", "font", "f", "key", "keybind",
		"url", "link", "open_url", "command", "cmd", "run_command", "suggest_command", "sgt",
		"copy", "clipboard", "copy_to_clipboard", "show_text", "tooltip", "ttp", "change_page"
	);

	/**
	 * The parts of the string: {@link String}s and {@link Expression}s.
	 */
	private final Object[] parts;
	private final StringMode mode;
	private final boolean safe;
	private final String template;
	private final int placeholders;

	private record ParsedTemplate(int configGeneration, @Nullable Component component) { }

	private volatile @Nullable ParsedTemplate parsed;

	private ComponentTemplate(Object[] parts, StringMode mode, boolean safe, String template, int placeholders) {
		this.parts = parts;
		this.mode = mode;
		this.safe = safe;
		this.template = template;
		this.placeholders = placeholders;
	}

	/**
	 * @param parts The parts of a string, {@link String}s and {@link Expression}s, like in a variable string.
	 * @param mode The mode to convert the values of the expressions to strings with.
	 * @param safe Whether to parse with the safe parser ({@link TextComponentParser#parseSafe(Object)}).
	 * @return A template, or null if the string can't be formatted with one (it's always correct to parse it fully).
	 */
	public static @Nullable ComponentTemplate create(Object[] parts, StringMode mode, boolean safe) {
		StringBuilder template = new StringBuilder();
		int placeholders = 0;
		boolean inTag = false;
		char previous = 0;
		for (Object part : parts) {
			if (part instanceof String string) {
				for (int i = 0; i < string.length(); i++) {
					char c = string.charAt(i);
					if (isPlaceholder(c))
						return null;
					if (c == '\\') { // escapes the next character
						i++;
					} else if (c == '<') {
						inTag = true;
					} else if (c == '>') {
						inTag = false;
					}
				}
				if (!string.isEmpty())
					previous = string.charAt(string.length() - 1);
				template.append(string);
			} else if (part instanceof Expression<?>) {
				if (inTag || previous == '&' || previous == '§' || previous == '\\' || placeholders == MAX_PLACEHOLDERS)
					return null;
				previous = (char) (FIRST_PLACEHOLDER + placeholders++);
				template.append(previous);
			} else {
				return null;
			}
		}
		if (placeholders == 0)
			return null;
		return new ComponentTemplate(parts, mode, safe, template.toString(), placeholders);
	}

	/**
	 * Evaluates the expressions and formats the string.
	 * @param event The event to evaluate the expressions with.
	 * @return The same component as parsing the complete string would give.
	 */
	public Component format(Event event) {
		String[] values = new String[placeholders];
		boolean plain = true;
		int index = 0;
		for (Object part : parts) {
			if (part instanceof Expression<?> expression) {
				// the same conversion as VariableString#toString(Event)
				String value = Classes.toString(expression.getArray(event), true, mode);
				values[index++] = value;
				if (plain && !isPlainValue(value))
					plain = false;
			}
		}

		TextComponentParser parser = TextComponentParser.instance();
		if (plain) {
			ParsedTemplate parsed = this.parsed;
			if (parsed == null || parsed.configGeneration != parser.configGeneration())
				this.parsed = parsed = parseTemplate(parser);
			if (parsed.component != null) {
				Component filled = fill(parsed.component, values);
				if (VERIFY)
					verify(filled, values, parser);
				return filled;
			}
		}

		// parse the complete string, exactly like before
		return parseComplete(values, parser);
	}

	private Component parseComplete(String[] values, TextComponentParser parser) {
		StringBuilder builder = new StringBuilder(template.length() + 16);
		int index = 0;
		for (Object part : parts) {
			if (part instanceof Expression<?>) {
				builder.append(values[index++]);
			} else {
				builder.append(part);
			}
		}
		return safe ? parser.parseSafe(builder.toString()) : parser.parse(builder.toString());
	}

	/**
	 * Debugging aid: with {@code -Dhyperskript.verifyTemplates=true}, every templated component is compared
	 * with the result of parsing the complete string, and differences are logged.
	 */
	private static final boolean VERIFY = Boolean.getBoolean("hyperskript.verifyTemplates");

	private void verify(Component filled, String[] values, TextComponentParser parser) {
		Component expected = parseComplete(values, parser);
		if (expected.equals(filled)) {
			Skript.info("[HyperSkript] Template verified: " + template);
		} else {
			Skript.warning("[HyperSkript] Template mismatch for '" + template + "' with values " + Arrays.toString(values)
				+ ": expected " + expected + " but got " + filled);
		}
	}

	private ParsedTemplate parseTemplate(TextComponentParser parser) {
		int generation = parser.configGeneration();
		if (parser.linkParseMode() != TextComponentParser.LinkParseMode.DISABLED
				|| !hasOnlyPositionIndependentTags(parser.reformatText(template)))
			return new ParsedTemplate(generation, null);

		Component component = parser.parseOrNull(template, safe);
		if (component == null)
			return new ParsedTemplate(generation, null);

		// every placeholder must be plain text in the result, exactly once
		int[] counts = new int[placeholders];
		if (!countPlaceholders(component, counts))
			return new ParsedTemplate(generation, null);
		for (int count : counts) {
			if (count != 1)
				return new ParsedTemplate(generation, null);
		}
		return new ParsedTemplate(generation, component);
	}

	/**
	 * Counts the placeholders in the contents of the text components of the tree.
	 * @return False if the tree has a virtual component (their output may depend on the text).
	 */
	private boolean countPlaceholders(Component component, int[] counts) {
		if (component instanceof VirtualComponent)
			return false;
		if (component instanceof TextComponent text) {
			String content = text.content();
			for (int i = 0; i < content.length(); i++) {
				char c = content.charAt(i);
				if (isPlaceholder(c)) {
					int index = c - FIRST_PLACEHOLDER;
					if (index >= counts.length)
						return false;
					counts[index]++;
				}
			}
		}
		for (Component child : component.children()) {
			if (!countPlaceholders(child, counts))
				return false;
		}
		return true;
	}

	private static Component fill(Component component, String[] values) {
		Component result = component;
		if (component instanceof TextComponent text) {
			String content = text.content();
			String filled = fill(content, values);
			if (filled != content)
				result = text.content(filled);
		}
		List<Component> children = component.children();
		if (!children.isEmpty()) {
			List<Component> filledChildren = null;
			for (int i = 0; i < children.size(); i++) {
				Component child = children.get(i);
				Component filledChild = fill(child, values);
				if (filledChild != child) {
					if (filledChildren == null)
						filledChildren = new ArrayList<>(children);
					filledChildren.set(i, filledChild);
				}
			}
			if (filledChildren != null)
				result = result.children(filledChildren);
		}
		return result;
	}

	/**
	 * @return The content with its placeholders replaced, or the same instance if it has none.
	 */
	private static String fill(String content, String[] values) {
		StringBuilder builder = null;
		int copiedUntil = 0;
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (!isPlaceholder(c))
				continue;
			if (builder == null)
				builder = new StringBuilder(content.length() + 32);
			builder.append(content, copiedUntil, i).append(values[c - FIRST_PLACEHOLDER]);
			copiedUntil = i + 1;
		}
		if (builder == null)
			return content;
		return builder.append(content, copiedUntil, content.length()).toString();
	}

	private static boolean hasOnlyPositionIndependentTags(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\') {
				i++;
				continue;
			}
			if (c != '<')
				continue;
			int end = i + 1;
			while (end < text.length() && text.charAt(end) != ':' && text.charAt(end) != '>' && text.charAt(end) != '<')
				end++;
			String name = text.substring(i + 1, end);
			if (name.startsWith("/") || name.startsWith("!"))
				name = name.substring(1);
			name = name.toLowerCase(Locale.ENGLISH);
			if (!name.startsWith("#") && !POSITION_INDEPENDENT_TAGS.contains(name))
				return false;
		}
		return true;
	}

	private static boolean isPlainValue(String value) {
		if (value.isEmpty())
			return false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '<' || c == '>' || c == '&' || c == '§' || c == '\\' || isPlaceholder(c))
				return false;
		}
		return true;
	}

	private static boolean isPlaceholder(char c) {
		return c >= FIRST_PLACEHOLDER && c < FIRST_PLACEHOLDER + MAX_PLACEHOLDERS;
	}

}
