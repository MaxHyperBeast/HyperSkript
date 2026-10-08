package org.skriptlang.skript.bukkit.text;

import ch.njol.skript.lang.VariableString;
import ch.njol.skript.lang.util.ConvertedExpression;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.converter.ConverterInfo;
import org.skriptlang.skript.lang.converter.Converters;

import java.util.function.Predicate;

/**
 * A variable string converted to a component, like {@link ConvertedExpression} with the string to component converter,
 * but formatted with a {@link ComponentTemplate} instead of parsing the complete string every time.
 */
@ApiStatus.Internal
public final class ComponentTemplateExpression extends ConvertedExpression<String, Component> {

	/**
	 * @return A component expression for the string, or null if it can't use a template.
	 */
	public static @Nullable ComponentTemplateExpression newInstance(VariableString string) {
		Object[] parts = string.getParts();
		if (parts == null)
			return null;
		// the converter that would be used otherwise, which parses safely
		ConverterInfo<String, Component> info = Converters.getConverterInfo(String.class, Component.class);
		if (info == null)
			return null;
		ComponentTemplate template = ComponentTemplate.create(parts, string.getMode(), true);
		if (template == null)
			return null;
		return new ComponentTemplateExpression(string, info, template);
	}

	private final ComponentTemplate template;

	private ComponentTemplateExpression(VariableString string, ConverterInfo<String, Component> info, ComponentTemplate template) {
		super(string, Component.class, info);
		this.template = template;
	}

	@Override
	public Component getSingle(Event event) {
		return template.format(event);
	}

	@Override
	public Component[] getArray(Event event) {
		return new Component[] {template.format(event)};
	}

	@Override
	public Component[] getAll(Event event) {
		return new Component[] {template.format(event)};
	}

	@Override
	public boolean check(Event event, Predicate<? super Component> checker) {
		return checker.test(template.format(event));
	}

}
