package dev.champions.client;

import dev.champions.core.module.InventorySnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

final class InventoryTelemetry {
	private InventoryTelemetry() {}

	static InventorySnapshot capture(Minecraft client) {
		if (client.player == null || client.player.containerMenu == null) return InventorySnapshot.EMPTY;
		AbstractContainerMenu menu = client.player.containerMenu;
		int nonEmpty = 0;
		int maxComponents = 0;
		int nonStandardCounts = 0;
		for (ItemStack stack : menu.getItems()) {
			if (stack == null || stack.isEmpty()) continue;
			nonEmpty++;
			maxComponents = Math.max(maxComponents, stack.getComponents().size());
			if (stack.getCount() > stack.getItem().getDefaultMaxStackSize()) nonStandardCounts++;
		}
		ItemStack carried = menu.getCarried();
		String carriedName = carried == null || carried.isEmpty() ? "empty" : sanitize(carried.getHoverName().getString());
		int carriedCount = carried == null || carried.isEmpty() ? 0 : carried.getCount();
		return new InventorySnapshot(true, menu.getClass().getSimpleName(), menu.containerId,
			menu.getStateId(), menu.slots.size(), nonEmpty, maxComponents, nonStandardCounts,
			carriedName, carriedCount);
	}

	private static String sanitize(String value) {
		String cleaned = value == null ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').strip();
		return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120) + "…";
	}
}
