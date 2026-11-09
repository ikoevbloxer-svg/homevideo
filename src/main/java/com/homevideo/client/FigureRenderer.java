package com.homevideo.client;

import com.homevideo.FigureEntity;
import com.homevideo.HomeVideo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

public class FigureRenderer extends BipedEntityRenderer<FigureEntity, BipedEntityModel<FigureEntity>> {
	private static final Identifier TEX = new Identifier(HomeVideo.ID, "textures/entity/figure.png");

	public FigureRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.ZOMBIE)), 0.0f);
	}

	@Override
	public Identifier getTexture(FigureEntity e) {
		if (e.isMirror()) {
			var p = MinecraftClient.getInstance().player;
			if (p != null) return p.getSkinTexture();
		}
		return TEX;
	}

	@Override
	protected void scale(FigureEntity e, MatrixStack m, float amount) {
		m.scale(0.92f, 1.18f, 0.92f);
	}
}
