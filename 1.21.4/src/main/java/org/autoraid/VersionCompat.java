package org.autoraid;

import net.minecraft.world.entity.player.Player;

/**
 * Minecraft {@code 1.21.4} specific API differences. Every version module provides its own copy
 * of this class; the shared code in {@code common/} only talks to it.
 */
final class VersionCompat {

	private VersionCompat() {
	}

	static int getSelectedSlot(Player player) {
		return player.getInventory().selected;
	}

	static void setSelectedSlot(Player player, int slot) {
		player.getInventory().selected = slot;
	}
}
