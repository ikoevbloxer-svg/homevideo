package com.homevideo.client;

import com.homevideo.HomeVideo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class HomeVideoClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(HomeVideo.FIGURE, FigureRenderer::new);
		HudRenderCallback.EVENT.register(VhsOverlay::render);
		ClientTickEvents.END_CLIENT_TICK.register(c -> VhsOverlay.tick());
		ClientPlayNetworking.registerGlobalReceiver(HomeVideo.FX_PACKET, (client, handler, buf, sender) -> {
			int type = buf.readVarInt();
			int ticks = buf.readVarInt();
			float inten = buf.readFloat();
			client.execute(() -> VhsOverlay.trigger(type, ticks, inten));
		});
		ClientPlayNetworking.registerGlobalReceiver(HomeVideo.TAPE_PACKET, (client, handler, buf, sender) -> {
			int idx = buf.readVarInt();
			int loop = buf.readVarInt();
			String name = buf.readString();
			client.execute(() -> client.setScreen(new TapeScreen(idx, loop, name)));
		});
	}
}
