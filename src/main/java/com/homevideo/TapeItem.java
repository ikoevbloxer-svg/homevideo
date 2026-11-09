package com.homevideo;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public class TapeItem extends Item {
	private final int index;

	public TapeItem(Settings settings, int index) {
		super(settings);
		this.index = index;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (!world.isClient && user instanceof ServerPlayerEntity sp) {
			Director.onTape(sp, index);
		}
		return TypedActionResult.success(stack, world.isClient);
	}
}
