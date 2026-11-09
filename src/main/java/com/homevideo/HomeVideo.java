package com.homevideo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class HomeVideo implements ModInitializer {
	public static final String ID = "homevideo";
	public static final Identifier FX_PACKET = new Identifier(ID, "fx");
	public static final Identifier TAPE_PACKET = new Identifier(ID, "tape");
	public static final Item[] TAPES = new Item[5];
	public static EntityType<FigureEntity> FIGURE;

	@Override
	public void onInitialize() {
		FIGURE = Registry.register(Registries.ENTITY_TYPE, new Identifier(ID, "figure"),
			EntityType.Builder.<FigureEntity>create(FigureEntity::new, SpawnGroup.MISC)
				.setDimensions(0.6f, 2.0f).maxTrackingRange(10).build("figure"));
		FabricDefaultAttributeRegistry.register(FIGURE, FigureEntity.createFigureAttributes());
		for (int i = 0; i < 5; i++) {
			TAPES[i] = Registry.register(Registries.ITEM, new Identifier(ID, "tape_" + (i + 1)),
				new TapeItem(new Item.Settings().maxCount(1), i + 1));
		}
		Director.init();
	}
}
