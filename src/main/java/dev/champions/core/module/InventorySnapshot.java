package dev.champions.core.module;

/** Read-only summary of the local player's current container state. */
public record InventorySnapshot(
	boolean available,
	String menuClass,
	int containerId,
	int stateId,
	int slotCount,
	int nonEmptySlots,
	int maximumComponentCount,
	int nonStandardStackCounts,
	String carriedItem,
	int carriedCount
) {
	public static final InventorySnapshot EMPTY = new InventorySnapshot(false, "none", -1, -1, 0, 0, 0, 0, "empty", 0);

	public InventorySnapshot {
		menuClass = menuClass == null || menuClass.isBlank() ? "unknown" : menuClass;
		carriedItem = carriedItem == null || carriedItem.isBlank() ? "empty" : carriedItem;
	}
}
