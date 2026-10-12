package com.fpsmod;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public class FpsModClient implements ClientModInitializer {
	private static boolean visible = true;
	private static double fps = 0;
	private static double clientTps = 0;
	private static double serverTps = 0;
	private static boolean serverTpsValid = false;

	private static long lastFrameNanos = 0;
	private static long lastClientTickNanos = 0;
	private static long lastServerTickNanos = 0;

	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fpsmod.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, KeyMapping.Category.MISC));

		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath("fpsmod", "fps_display"),
				FpsModClient::onHudRender);

		// 客户端 tick 率：用于联机时的 TPS 估算值
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				visible = !visible;
			}
			long now = System.nanoTime();
			if (lastClientTickNanos != 0) {
				double dt = (now - lastClientTickNanos) / 1_000_000_000.0;
				if (dt > 0 && dt < 1.0) {
					double tps = 1.0 / dt;
					clientTps = clientTps == 0 ? tps : clientTps * 0.9 + tps * 0.1;
				}
			}
			lastClientTickNanos = now;
		});

		// 真实服务器 TPS：仅在集成服务器（单机/局域网）运行时触发
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.nanoTime();
			if (lastServerTickNanos != 0) {
				double dt = (now - lastServerTickNanos) / 1_000_000_000.0;
				if (dt > 0 && dt < 1.0) {
					double tps = 1.0 / dt;
					serverTps = serverTps == 0 ? tps : serverTps * 0.9 + tps * 0.1;
					serverTpsValid = true;
				}
			}
			lastServerTickNanos = now;
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> lastServerTickNanos = 0);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			serverTpsValid = false;
			serverTps = 0;
		});
	}

	private static void onHudRender(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (!visible || client.gui.hud.isHidden() || client.font == null) {
			return;
		}

		long now = System.nanoTime();
		if (lastFrameNanos != 0) {
			double dt = (now - lastFrameNanos) / 1_000_000_000.0;
			if (dt > 0 && dt < 1.0) {
				double f = 1.0 / dt;
				fps = fps == 0 ? f : fps * 0.95 + f * 0.05;
			}
		}
		lastFrameNanos = now;

		boolean realTps = serverTpsValid;
		double tps = realTps ? serverTps : clientTps;
		String fpsStr = String.valueOf(Math.round(fps));
		String tpsStr = String.format("%s%.1f", realTps ? "" : "~", tps);
		String labelFps = "FPS: ";
		String labelTps = "   TPS: ";

		int x = 4;
		int width = client.font.width(labelFps) + client.font.width(fpsStr)
				+ client.font.width(labelTps) + client.font.width(tpsStr);
		int height = client.font.lineHeight;
		int y = (client.getWindow().getGuiScaledHeight() - height) / 2;

		// FPS 数值按档位着色：蓝>=90 绿>=60 黄>=30 红<30
		int fpsColor;
		if (fps >= 90) {
			fpsColor = 0xFF5555FF;
		} else if (fps >= 60) {
			fpsColor = 0xFF55FF55;
		} else if (fps >= 30) {
			fpsColor = 0xFFFFFF55;
		} else {
			fpsColor = 0xFFFF5555;
		}

		// TPS 数值着色：<=15 红色，>15 绿色
		int tpsColor = tps <= 15.0 ? 0xFFFF5555 : 0xFF55FF55;

		g.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0x80000000);
		g.text(client.font, labelFps, x, y, 0xFFFFFFFF);
		x += client.font.width(labelFps);
		g.text(client.font, fpsStr, x, y, fpsColor);
		x += client.font.width(fpsStr);
		g.text(client.font, labelTps, x, y, 0xFFFFFFFF);
		x += client.font.width(labelTps);
		g.text(client.font, tpsStr, x, y, tpsColor);
	}
}
