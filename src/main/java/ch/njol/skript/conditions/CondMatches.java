
package ch.njol.skript.conditions;

import ch.njol.skript.Skript;
import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Example;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SimplifiedCondition;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.regex.Pattern;

@Name("Matches")
@Description("Checks whether the defined strings match the input regexes (Regular expressions).")
@Example("""
	on chat:
		if message partially matches "\\d":
			send "Message contains a digit!"
		if message doesn't match "[A-Za-z]+":
			send "Message doesn't only contain letters!"
	""")
@Since("2.5.2")
public class CondMatches extends Condition {
	
	static {
		Skript.registerCondition(CondMatches.class,
			"%strings% (1¦match[es]|2¦do[es](n't| not) match) %strings%",
			"%strings% (1¦partially match[es]|2¦do[es](n't| not) partially match) %strings%");
	}
	
	@SuppressWarnings("null")
	Expression<String> strings;
	@SuppressWarnings("null")
	Expression<String> regex;
	
	boolean partial;

	/**
	 * The patterns of the last regexes, as the same regexes are usually used again (often a literal).
	 * Patterns are compiled when first needed, so an invalid regex only fails when it's reached.
	 */
	private record CompiledRegexes(String[] regexes, Pattern[] patterns) {

		Pattern get(int index) {
			Pattern pattern = patterns[index];
			if (pattern == null)
				patterns[index] = pattern = Pattern.compile(regexes[index]);
			return pattern;
		}

	}

	private volatile @Nullable CompiledRegexes lastRegexes;

	private CompiledRegexes getCompiled(String[] regexes) {
		CompiledRegexes last = lastRegexes;
		if (last != null && Arrays.equals(last.regexes, regexes))
			return last;
		CompiledRegexes compiled = new CompiledRegexes(regexes.clone(), new Pattern[regexes.length]);
		lastRegexes = compiled;
		return compiled;
	}
	
	@Override
	@SuppressWarnings({"unchecked", "null"})
	public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
		strings = (Expression<String>) exprs[0];
		regex = (Expression<String>) exprs[1];
		partial = matchedPattern == 1;
		setNegated(parseResult.mark == 1);
		return true;
	}
	
	@Override
	public boolean check(Event e) {
		String[] txt = strings.getAll(e);
		String[] regexes = regex.getAll(e);
		if (txt.length < 1 || regexes.length < 1) return false;
		boolean stringAnd = strings.getAnd();
		boolean regexAnd = regex.getAnd();
		// same and/or logic as before, without parallel streams; each regex is compiled once (in order, when first needed)
		CompiledRegexes compiled = getCompiled(regexes);
		boolean result = stringAnd;
		for (String str : txt) {
			boolean strResult = regexAnd;
			for (int i = 0; i < regexes.length; i++) {
				if (matches(str, compiled.get(i)) != regexAnd) {
					strResult = !regexAnd;
					break;
				}
			}
			if (strResult != stringAnd) {
				result = !stringAnd;
				break;
			}
		}
		return result == isNegated();
	}
	
	public boolean matches(String str, Pattern pattern) {
		// matcher(str).matches() is what String#matches does, without compiling the pattern again
		return partial ? pattern.matcher(str).find() : pattern.matcher(str).matches();
	}

	@Override
	public Condition simplify() {
		if (strings instanceof Literal<String> && regex instanceof Literal<String>)
			return SimplifiedCondition.fromCondition(this);
		return this;
	}

	@Override
	public String toString(@Nullable Event e, boolean debug) {
		return strings.toString(e, debug) + " " + (isNegated() ? "doesn't match" : "matches") + " " + regex.toString(e, debug);
	}
	
}
