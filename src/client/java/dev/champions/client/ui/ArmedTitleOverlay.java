package dev.champions.client.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Small identification box in the top-right of the main menu: ChampionsCode brand,
 * "ARMED" status and the installed mod version. Registered once from client init and
 * drawn after the title screen renders so it sits on top.
 */
public final class ArmedTitleOverlay {
	private static final String BRAND = "CHAMPIONSCODE";
	private static final String STATUS = "ARMED";

	private ArmedTitleOverlay() {}

	public static void register() {
		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof TitleScreen)) return;
			ScreenEvents.afterExtract(screen).register((ignored, graphics, mouseX, mouseY, delta) ->
				render(graphics, client.font, scaledWidth));
		});
	}

	private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int screenWidth) {
		String version = "v" + FabricLoader.getInstance().getModContainer("champions")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
		int boxWidth = 16 + Math.max(font.width(BRAND), font.width(STATUS) + font.width("  ") + font.width(version));
		int boxHeight = 38;
		int x = screenWidth - boxWidth - 8;
		int y = 8;
		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0xE60B1726);
		graphics.fill(x, y, x + 3, y + boxHeight, 0xFF18D9C7);
		graphics.fill(x, y + 2, x + boxWidth, y + 3, 0xFF24435D);
		graphics.text(font, BRAND, x + 10, y + 6, 0xFF18D9C7, false);
		graphics.text(font, STATUS, x + 10, y + 21, 0xFFFBBF24, true);
		graphics.text(font, version, x + 10 + font.width(STATUS) + font.width("  "), y + 21, 0xFFA8B3C7, false);
	}
}
