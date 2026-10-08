package org.skriptlang.skript.bukkit.text.elements.expressions;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Example;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.expressions.base.SimplePropertyExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.VariableString;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.bukkit.text.ComponentTemplate;
import org.skriptlang.skript.bukkit.text.TextComponentParser;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

@Name("Colored/Formatted/Uncolored")
@Description("Parses or removes colors and, optionally, chat styles in/from a message.")
@Example("""
	on chat:
		set message to colored message # only safe tags, such as colors, will be parsed
	""")
@Example("""
	command /fade <player>:
		trigger:
			set the display name of the player-argument to the uncolored display name of the player-argument
	""")
@Example("""
	command /format <text>:
		trigger:
			message formatted text-argument # parses all tags, but this is okay as the output is sent back to the executor
	""")
@Since({
	"2.0",
	"2.15 ('uncolored' vs 'unformatted' distinction)"
})
public class ExprColored extends SimplePropertyExpression<String, Object> {

	public static void register(SyntaxRegistry syntaxRegistry) {
		syntaxRegistry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprColored.class, Object.class)
			.supplier(ExprColored::new)
			.priority(DEFAULT_PRIORITY)
			.addPatterns("[negated:(un|non)[-]](colo[u]r-|colo[u]red )%strings%",
				"[negated:(un|non)[-]](format-|formatted )%strings%")
			.build());
	}

	private boolean isColor;
	private boolean isFormat;
	private @Nullable ComponentTemplate template;

	@Override
	public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
		isColor = !parseResult.hasTag("negated");
		isFormat = matchedPattern == 1;
		if (!super.init(expressions, matchedPattern, isDelayed, parseResult))
			return false;
		// a string with expressions is formatted with a template, which only parses its static parts once
		if (isColor && getExpr() instanceof VariableString string && !string.isSimple()) {
			Object[] parts = string.getParts();
			template = parts == null ? null : ComponentTemplate.create(parts, string.getMode(), !isFormat);
			if (template != null)
				setExpr(new TemplateSource(string));
		}
		return true;
	}

	@Override
	protected Object[] get(Event event, String[] source) {
		ComponentTemplate template = this.template;
		if (template != null) // source is TemplateSource's placeholder, the template evaluates the string itself
			return new Component[] {template.format(event)};
		return super.get(event, source);
	}

	/**
	 * Stands in for the string when a {@link ComponentTemplate} formats it, so the complete string isn't built for nothing.
	 * Everything else is the same as the string.
	 */
	private static final class TemplateSource extends SimpleExpression<String> {

		private final VariableString string;

		private TemplateSource(VariableString string) {
			this.string = string;
		}

		@Override
		protected String[] get(Event event) {
			return new String[] {""};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return string.toString(event, debug);
		}

	}

	@Override
	public Object convert(String string) {
		TextComponentParser parser = TextComponentParser.instance();
		if (isColor) {
			return isFormat ? parser.parse(string) : parser.parseSafe(string);
		}
		return isFormat ? parser.stripFormatting(string) : parser.stripSafeFormatting(string);
	}

	@Override
	public Class<?> getReturnType() {
		return isColor ? Component.class : String.class;
	}

	@Override
	protected String getPropertyName() {
		if (isColor) {
			return isFormat ? "formatted" : "colored";
		}
		return isFormat ? "unformatted" : "uncolored";
	}

	/**
	 * @deprecated This method is only available for compatibility purposes.
	 */
	@Deprecated(since = "2.15", forRemoval = true)
	public boolean isUnsafeFormat() {
		return isColor && isFormat;
	}

}
