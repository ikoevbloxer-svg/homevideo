package com.homevideo.client;

import java.util.Locale;
import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class VhsOverlay {
	private VhsOverlay() {}

	public static final int GLITCH = 0, STATIC = 1, REWIND = 2, FLASH = 3, BLACK = 4;
	private static final Random R = new Random();
	private static int glitchTicks, staticTicks, rewindTicks, flashTicks, blackTicks, blackTotal = 1;
	private static float glitchInt = 1f;
	private static int clockSeconds = 22 * 3600 + 47 * 60;
	private static int tickCount = 0;

	public static void trigger(int type, int ticks, float inten) {
		switch (type) {
			case GLITCH -> { glitchTicks = Math.max(glitchTicks, ticks); glitchInt = Math.max(inten, 0.3f); }
			case STATIC -> staticTicks = Math.max(staticTicks, ticks);
			case REWIND -> rewindTicks = ticks;
			case FLASH -> flashTicks = ticks;
			case BLACK -> { blackTicks = ticks; blackTotal = Math.max(ticks, 1); }
			default -> { }
		}
	}

	public static void tick() {
		tickCount++;
		if (rewindTicks > 0) {
			clockSeconds -= 47;
			rewindTicks--;
		} else if (tickCount % 20 == 0) {
			clockSeconds++;
		}
		if (glitchTicks > 0) {
			glitchTicks--;
			if (R.nextInt(6) == 0) clockSeconds += R.nextInt(900) - 300;
		}
		if (staticTicks > 0) staticTicks--;
		if (flashTicks > 0) flashTicks--;
		if (blackTicks > 0) blackTicks--;
	}

	public static void render(DrawContext ctx, float delta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		draw(ctx, mc, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), true);
	}

	public static void draw(DrawContext ctx, MinecraftClient mc, int w, int h, boolean hud) {
		for (int y = 0; y < h; y += 3) ctx.fill(0, y, w, y + 1, 0x28000000);
		ctx.fillGradient(0, 0, w, h / 6, 0xA0000000, 0x00000000);
		ctx.fillGradient(0, h - h / 6, w, h, 0x00000000, 0xA0000000);
		for (int i = 0; i < 14; i++) {
			int a = (int) (0x80 * Math.pow(1.0 - i / 14.0, 2));
			int col = a << 24;
			ctx.fill(i * 4, 0, i * 4 + 4, h, col);
			ctx.fill(w - i * 4 - 4, 0, w - i * 4, h, col);
		}
		for (int i = 0; i < 26; i++) {
			int x = R.nextInt(w), y = R.nextInt(h), g = 120 + R.nextInt(120);
			ctx.fill(x, y, x + 1 + R.nextInt(3), y + 1, 0x30000000 | (g << 16) | (g << 8) | g);
		}
		int band = (tickCount * 3) % (h + 60) - 30;
		ctx.fill(0, band, w, band + 10, 0x0CFFFFFF);
		ctx.fill(0, band + 10, w, band + 12, 0x16FFFFFF);

		if (glitchTicks > 0) {
			int n = 1 + (int) (glitchInt * 3);
			for (int i = 0; i < n; i++) {
				int y = R.nextInt(h), hh = 3 + R.nextInt(22);
				ctx.fill(0, y, w, y + hh, ((0x20 + R.nextInt(0x50)) << 24) | 0xFFFFFF);
				ctx.fill(0, y, w, y + 1, 0x70FF3030);
				ctx.fill(0, y + hh, w, y + hh + 1, 0x7030C0FF);
				int rx = R.nextInt(w), rw = 20 + R.nextInt(120);
				ctx.fill(rx, y, rx + rw, y + 2, 0xAAFFFFFF);
			}
			if (glitchInt > 1.3f && R.nextInt(3) == 0) ctx.fill(0, 0, w, h, (R.nextInt(60) << 24));
		}
		if (staticTicks > 0) {
			for (int i = 0; i < 700; i++) {
				int x = R.nextInt(w), y = R.nextInt(h), g = R.nextInt(256), s = 1 + R.nextInt(4);
				ctx.fill(x, y, x + s, y + s, 0xC0000000 | (g << 16) | (g << 8) | g);
			}
		}
		if (flashTicks > 0) ctx.fill(0, 0, w, h, Math.min(255, flashTicks * 60) << 24 | 0xFFFFFF);
		if (blackTicks > 0) {
			float k = 1f - (blackTicks / (float) blackTotal);
			ctx.fill(0, 0, w, h, ((int) (255 * Math.min(1f, k * 1.6f))) << 24);
		}
		if (!hud) return;
		boolean rewinding = rewindTicks > 0;
		if ((tickCount / 20) % 2 == 0 || rewinding) ctx.fill(12, 14, 20, 22, 0xFFE02020);
		text(ctx, mc, rewinding ? "<< REW" : "REC", 26, 14, 0xFFFFFFFF);
		text(ctx, mc, rewinding ? "SP" : "PLAY >", w - 70, 14, 0xFFFFFFFF);
		int sec = Math.floorMod(clockSeconds, 86400);
		String time = String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
		text(ctx, mc, time, w - 76, h - 36, 0xFFFFE9A8);
		text(ctx, mc, "OCT 31 1994", w - 100, h - 24, 0xFFFFE9A8);
	}

	private static void text(DrawContext ctx, MinecraftClient mc, String s, int x, int y, int color) {
		ctx.drawText(mc.textRenderer, s, x + 1, y, 0x70FF2020, false);
		ctx.drawText(mc.textRenderer, s, x - 1, y, 0x7020A0FF, false);
		ctx.drawText(mc.textRenderer, s, x, y, color, false);
	}
}
