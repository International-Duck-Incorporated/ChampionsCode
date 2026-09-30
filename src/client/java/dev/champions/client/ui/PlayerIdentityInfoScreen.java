package dev.champions.client.ui;

import dev.champions.core.module.net.PlayerProfileDetails;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;

/** Compact identity card shown only when a ChampionsCode GUI is already open. */
public final class PlayerIdentityInfoScreen extends Screen {
	private final Screen parent;
	private final PlayerProfileDetails details;
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;

	public PlayerIdentityInfoScreen(Screen parent, PlayerProfileDetails details) {
		super(Component.literal("Player identity"));
		this.parent = parent;
		this.details = details;
	}

	@Override
	protected void init() {
		panelWidth = Math.min(420, Math.max(300, width - 30));
		panelHeight = 188;
		panelX = (width - panelWidth) / 2;
		panelY = (height - panelHeight) / 2;

		UUID uuid = UUID.fromString(details.identity().dashedUuid());
		// Leave the profile unresolved so Minecraft's own profile resolver fetches
		// the signed skin properties instead of rendering the default face.
		ResolvableProfile profile = ResolvableProfile.createUnresolved(uuid);
		PlayerFaceWidget face = new PlayerFaceWidget(48, profile);
		face.setX(panelX + 16);
		face.setY(panelY + 46);
		addRenderableWidget(face);

		int buttonY = panelY + panelHeight - 31;
		addRenderableWidget(Button.builder(Component.literal("Copy UUID"), button ->
			minecraft.keyboardHandler.setClipboard(details.identity().dashedUuid()))
			.bounds(panelX + 12, buttonY, 92, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Copy skin URL"), button ->
			minecraft.keyboardHandler.setClipboard(details.identity().skinUrl() == null ? "" : details.identity().skinUrl()))
			.bounds(panelX + 110, buttonY, 112, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Copy profile links"), button ->
			minecraft.keyboardHandler.setClipboard(details.playerDbProfileUrl() + "\n" + details.labyProfileUrl()))
			.bounds(panelX + 228, buttonY, 128, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(panelX + panelWidth - 58, panelY + 8, 46, 18).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Keep the existing ChampionsCode workspace visible behind the card.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x78000000);
		graphics.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2, panelY + panelHeight + 2, 0xFF000000);
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFA11141B);
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 34, 0xFF202737);
		graphics.fill(panelX, panelY + 32, panelX + panelWidth, panelY + 34, 0xFF8B5CF6);
		graphics.text(font, "PLAYER IDENTITY", panelX + 12, panelY + 12, 0xFFC4B5FD, false);

		int textX = panelX + 78;
		int y = panelY + 45;
		line(graphics, "Name", details.identity().name(), textX, y); y += 15;
		line(graphics, "UUID", details.identity().dashedUuid(), textX, y); y += 15;
		line(graphics, "Model", details.identity().skinModel(), textX, y); y += 15;
		line(graphics, "Cape", details.identity().capeUrl() == null ? "none" : "published", textX, y); y += 15;
		line(graphics, "PlayerDB", details.playerDbStatus(), textX, y); y += 15;
		line(graphics, "Laby.net", "public profile link available", textX, y);

		if (details.playerDbCachedAt() != null) {
			graphics.text(font, fit("PlayerDB cache: " + details.playerDbCachedAt(), panelWidth - 28),
				panelX + 14, panelY + 130, 0xFF8992A8, false);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void line(GuiGraphicsExtractor graphics, String label, String value, int x, int y) {
		graphics.text(font, label + ":", x, y, 0xFF8992A8, false);
		graphics.text(font, fit(value, panelX + panelWidth - x - 14), x + 62, y, 0xFFE6EAF2, false);
	}

	private String fit(String value, int maxWidth) {
		if (value == null) return "-";
		if (font.width(value) <= maxWidth) return value;
		return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("..."))) + "...";
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }
}
