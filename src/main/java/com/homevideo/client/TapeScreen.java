package com.homevideo.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class TapeScreen extends Screen {
	private static final Random R = new Random();
	private final boolean fin;
	private final List<String> lines;
	private final int total;
	private double progress = 0;
	private boolean fast = false;

	public TapeScreen(int index, int loop, String name) {
		super(Text.empty());
		this.fin = index >= 6;
		this.lines = Texts.get(index, loop, name);
		int t = 0;
		for (String l : lines) t += l.length() + 14;
		this.total = t;
		VhsOverlay.trigger(VhsOverlay.STATIC, 25, 1f);
		if (fin) VhsOverlay.trigger(VhsOverlay.GLITCH, 400, 1.6f);
	}

	@Override public boolean shouldPause() { return !fin; }
	@Override public boolean shouldCloseOnEsc() { return !fin; }

	@Override
	public void tick() {
		progress += fin ? 1.5 : (fast ? 4.0 : 1.3);
		if (fin && progress > total + 60) this.close();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		fast = true;
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 32) { fast = true; return true; }
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(DrawContext ctx, int mx, int my, float delta) {
		ctx.fill(0, 0, width, height, 0xFF050506);
		for (int i = 0; i < 160; i++) {
			int x = R.nextInt(width), y = R.nextInt(height), g = 40 + R.nextInt(120);
			ctx.fill(x, y, x + 1 + R.nextInt(3), y + 1, 0x30000000 | (g << 16) | (g << 8) | g);
		}
		int left = 40, w = width - 80, top = 56, bottom = height - 44;
		List<String> vis = new ArrayList<>();
		int rem = (int) progress;
		for (String l : lines) {
			if (rem <= 0) break;
			vis.add(l.substring(0, Math.min(l.length(), rem)));
			rem -= l.length() + 14;
		}
		int[] hs = new int[vis.size()];
		int total = 0;
		for (int i = 0; i < vis.size(); i++) {
			hs[i] = textRenderer.getWrappedLinesHeight(vis.get(i), w) + 7;
			total += hs[i];
		}
		int scroll = Math.max(0, total - (bottom - top));
		ctx.enableScissor(0, top - 4, width, bottom);
		int y = top - scroll;
		for (int i = 0; i < vis.size(); i++) {
			String s = vis.get(i);
			int col = s.startsWith("[") ? 0xFF8FA3B5 : 0xFFEDEDED;
			ctx.drawTextWrapped(textRenderer, Text.literal(s), left, y, w, col);
			y += hs[i];
		}
		ctx.disableScissor();
		ctx.drawText(textRenderer, fin ? "REC" : "PLAY >    CH 03", left, 24, 0xFFFFFFFF, false);
		if (!fin) {
			String hint = progress >= this.total ? "ESC - извлечь кассету" : "ЛКМ / ПРОБЕЛ - ускорить     ESC - стоп";
			ctx.drawText(textRenderer, hint, left, height - 28, 0xFF707A84, false);
		}
		VhsOverlay.draw(ctx, client, width, height, false);
	}
}
