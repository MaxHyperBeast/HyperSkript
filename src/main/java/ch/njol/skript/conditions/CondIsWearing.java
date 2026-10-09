package ch.njol.skript.conditions;

import ch.njol.skript.Skript;
import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.conditions.base.PropertyCondition;
import ch.njol.skript.conditions.base.PropertyCondition.PropertyType;
import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Example;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Name("Is Wearing")
@Description("Checks whether an entity is wearing some items (usually armor).")
@Example("player is wearing an iron chestplate and iron leggings")
@Example("target is wearing wolf armor")
@Since("1.0")
public class CondIsWearing extends Condition {

	private static final boolean HAS_CAN_USE_SLOT_METHOD = Skript.methodExists(LivingEntity.class, "canUseEquipmentSlot", EquipmentSlot.class);
	private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();
	private static final boolean HAS_BODY_SLOT = Skript.fieldExists(EquipmentSlot.class, "BODY");
	
	static {
		PropertyCondition.register(CondIsWearing.class, "wearing %itemtypes%", "livingentities");
	}
	
	@SuppressWarnings("NotNullFieldNotInitialized")
	private Expression<LivingEntity> entities;
	@SuppressWarnings("NotNullFieldNotInitialized")
	private Expression<ItemType> types;
	
	@SuppressWarnings({"unchecked", "null"})
	@Override
	public boolean init(Expression<?>[] vars, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
		entities = (Expression<LivingEntity>) vars[0];
		types = (Expression<ItemType>) vars[1];
		setNegated(matchedPattern == 1);
		return true;
	}
	
	@Override
	public boolean check(Event event) {
		ItemType[] cachedTypes = types.getAll(event);

		return entities.check(event, entity -> {
			EntityEquipment equipment = entity.getEquipment();
			if (equipment == null)
				return false; // spigot nullability, no identifier as to why this occurs

			// the same as filtering the slots with a stream, without the stream
			List<ItemStack> contentList = new ArrayList<>(EQUIPMENT_SLOTS.length);
			for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
				boolean usable;
				// this method was added in 1.20.6
				if (HAS_CAN_USE_SLOT_METHOD) {
					usable = entity.canUseEquipmentSlot(slot);
				} else if (HAS_BODY_SLOT && slot == EquipmentSlot.BODY) { // body slot was added in 1.20.5
					// this may change in the future, but for now this is the only way to figure out
					// if the entity can use the body slot
					usable = entity instanceof Horse
						|| entity instanceof Wolf
						|| entity instanceof Llama;
				} else {
					usable = true;
				}
				if (usable)
					contentList.add(equipment.getItem(slot));
			}
			ItemStack[] contents = contentList.toArray(new ItemStack[0]);

			return SimpleExpression.check(cachedTypes, type -> {
				for (ItemStack content : contents) {
					if (type.isOfType(content) ^ type.isAll())
						return !type.isAll();
				}
				return type.isAll();
			}, false, false);
		}, isNegated());
	}

	@Override
	public String toString(@Nullable Event event, boolean debug) {
		return PropertyCondition.toString(this, PropertyType.BE, event, debug, entities,
				"wearing " + types.toString(event, debug));
	}
	
}
