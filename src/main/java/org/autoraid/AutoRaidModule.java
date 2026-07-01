package org.autoraid;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.util.Locale;
import java.util.UUID;

/**
 * Automatically drinks an Ominous Bottle as soon as the raid victory boss bar appears.
 * <p>
 * If no bottle is on the hotbar, one is pulled from the inventory into the configured
 * hotbar slot first. When the supply runs out, the module can optionally disconnect.
 *
 * @author eclipxia
 */
public class AutoRaidModule extends ToggleableModule {

	/**
	 * Which hotbar slot to move a bottle into when none is already on the hotbar.
	 * Displayed 1-9, stored/used internally as an index (value - 1).
	 */
	private final NumberSetting<Integer> hotbarSlot =
			new NumberSetting<>("HotbarSlot", 9, 1, 9).incremental(1);

	/**
	 * Ticks to wait after victory is detected before drinking (20 ticks = 1 second).
	 */
	private final NumberSetting<Integer> delay =
			new NumberSetting<>("Delay", 10, 0, 100).incremental(1);

	private final BooleanSetting skipIfOmen =
			new BooleanSetting("SkipIfOmen", "Don't drink if Bad Omen or Raid Omen is already active", true);

	private final BooleanSetting autoDisconnect =
			new BooleanSetting("AutoDisconnect", "Disconnect when no Ominous Bottles are left", false);

	private final BooleanSetting textDetection =
			new BooleanSetting("TextDetection", "Also detect victory from the boss bar text (for servers that strip translation keys)", true);

	private final BooleanSetting notifications =
			new BooleanSetting("Notifications", "Print chat messages when acting", true);

	/**
	 * Set from the packet thread, consumed on the main (tick) thread.
	 */
	private volatile boolean victoryFlag = false;

	//drink sequence state machine
	private int stage = 0; //0 = idle
	private int stageTicks = 0;
	private int delayCounter = 0;
	private int targetSlot = 0;   //hotbar index (0-8) holding the bottle to drink
	private int previousSlot = 0; //slot to restore after drinking
	private int drinkTimer = 0;   //ticks left to hold "use" (bounds the drink to one bottle)
	private int guardTicks = 0;   //ticks spent cancelling any extra auto-use
	private int startCount = 0;   //held bottle count when the drink began (to detect consumption)
	private int cooldown = 0;     //ticks to ignore new victories after a drink (anti double-trigger)

	public AutoRaidModule() {
		super("AutoRaid", "Automatically drinks an Ominous Bottle on raid victory", ModuleCategory.PLAYER);

		this.registerSettings(
				this.hotbarSlot,
				this.delay,
				this.skipIfOmen,
				this.autoDisconnect,
				this.textDetection,
				this.notifications
		);
	}

	/**
	 * Boss bar detection. Packets may be handled off the main thread, so we only
	 * flag the victory here and act on the next tick.
	 */
	@Subscribe
	private void onPacket(EventPacket.Receive event) {
		final Packet<?> packet = event.getPacket();
		if (!(packet instanceof ClientboundBossEventPacket bossPacket)) {
			return;
		}

		final boolean[] victory = {false};
		bossPacket.dispatch(new ClientboundBossEventPacket.Handler() {
			@Override
			public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
				if (isVictory(name)) {
					victory[0] = true;
				}
			}

			@Override
			public void remove(UUID id) {
			}

			@Override
			public void updateProgress(UUID id, float progress) {
			}

			@Override
			public void updateName(UUID id, Component name) {
				if (isVictory(name)) {
					victory[0] = true;
				}
			}

			@Override
			public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
			}

			@Override
			public void updateProperties(UUID id, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
			}
		});

		if (victory[0]) {
			this.victoryFlag = true;
		}
	}

	/**
	 * Main tick loop: kicks off and advances the drink sequence.
	 */
	@Subscribe
	private void onUpdate(EventUpdate event) {
		if (mc.player == null || mc.level == null || mc.gameMode == null) {
			return;
		}

		if (this.cooldown > 0) {
			this.cooldown--;
		}

		//start a new sequence when victory was flagged and we're not already busy
		if (this.victoryFlag) {
			this.victoryFlag = false;
			if (this.stage == 0 && this.cooldown == 0) {
				//don't waste bottles if an omen is already active
				if (this.skipIfOmen.getValue() && hasOmen()) {
					notify("Omen already active - skipping drink");
				} else {
					this.stage = 1;
					this.stageTicks = 0;
					this.delayCounter = this.delay.getValue();
					this.previousSlot = mc.player.getInventory().getSelectedSlot();
					notify("Raid victory detected");
				}
			}
		}

		if (this.stage == 0) {
			return;
		}

		//optional pre-drink delay
		if (this.delayCounter > 0) {
			this.delayCounter--;
			return;
		}

		//safety timeout (~5 seconds) so we never get stuck
		if (++this.stageTicks > 100) {
			resetSequence();
			return;
		}

		switch (this.stage) {
			case 1 -> ensureBottleOnHotbar();
			case 2 -> {
				selectSlot(this.targetSlot);
				this.stage = 3;
			}
			case 3 -> startDrinking();
			case 4 -> continueDrinking();
			case 5 -> guardAgainstExtraDrink();
			case 6 -> finish();
			default -> resetSequence();
		}
	}

	/**
	 * Stage 3: begin using the bottle.
	 * <p>
	 * The Ominous Bottle is a consumable with a drink animation, so a single {@code useItem}
	 * only starts the use - it must be held to completion (see stage 4).
	 */
	private void startDrinking() {
		this.startCount = heldBottleCount();
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		notify("Drinking Ominous Bottle");

		if (mc.player.isUsingItem()) {
			//consumable with a drink animation: hold "use" so it can finish, bounded to one bottle
			this.drinkTimer = mc.player.getUseItemRemainingTicks() + 2;
			mc.options.keyUse.setDown(true);
			this.stage = 4;
		} else {
			//instant-use item, already consumed
			this.stage = 6;
		}
	}

	/**
	 * Stage 4: hold the use key until the drink finishes, then stop immediately. The primary
	 * stop signal is the held bottle count dropping - the instant one bottle is consumed we
	 * release and cancel any use, so vanilla can never auto-start a second drink.
	 */
	private void continueDrinking() {
		//one bottle consumed -> we're done, cancel anything still in progress
		if (heldBottleCount() < this.startCount) {
			stopHolding();
			this.guardTicks = 0;
			this.stage = 5;
			return;
		}

		//still drinking the first bottle (bounded by drinkTimer as a failsafe)
		if (mc.player.isUsingItem() && this.drinkTimer > 0) {
			this.drinkTimer--;
			mc.options.keyUse.setDown(true);
			return;
		}

		//timed out or use ended without a consumption
		stopHolding();
		this.guardTicks = 0;
		this.stage = 5;
	}

	/**
	 * Stage 5: for a few ticks, cancel any extra drink vanilla may auto-start (while the key
	 * state settles) before it can consume another bottle. Releasing early consumes nothing.
	 */
	private void guardAgainstExtraDrink() {
		stopHolding();
		if (++this.guardTicks >= 3) {
			this.stage = 6;
		}
	}

	/**
	 * Stage 6: restore the previously held slot and handle the out-of-bottles case.
	 */
	private void finish() {
		selectSlot(this.previousSlot);
		//briefly ignore further victory packets so one raid win never drinks twice
		this.cooldown = 30;
		final boolean anyLeft = InventoryUtils.findItem(Items.OMINOUS_BOTTLE, false, true) >= 0;
		if (!anyLeft && this.autoDisconnect.getValue()) {
			disconnect();
		}
		resetSequence();
	}

	/**
	 * Stop holding the use key and cancel any in-progress use (early release consumes nothing).
	 */
	private void stopHolding() {
		mc.options.keyUse.setDown(false);
		if (mc.player.isUsingItem()) {
			mc.gameMode.releaseUsingItem(mc.player);
		}
	}

	/**
	 * Count of Ominous Bottles currently held in the main hand (0 if not holding one).
	 */
	private int heldBottleCount() {
		final ItemStack held = mc.player.getMainHandItem();
		return held.is(Items.OMINOUS_BOTTLE) ? held.getCount() : 0;
	}

	/**
	 * Stage 1: make sure a bottle is on the hotbar, moving one up from the inventory if needed.
	 */
	private void ensureBottleOnHotbar() {
		final int hotbar = InventoryUtils.findItemHotbar(Items.OMINOUS_BOTTLE);
		if (hotbar >= 0) {
			this.targetSlot = hotbar;
			this.stage = 2;
			return;
		}

		//not on the hotbar - look in the rest of the inventory
		final int inventorySlot = InventoryUtils.findItem(Items.OMINOUS_BOTTLE, false, false);
		if (inventorySlot >= 0) {
			final int place = this.hotbarSlot.getValue() - 1;
			InventoryUtils.swapSlots(inventorySlot, place);
			this.targetSlot = place;
			//stay in stage 1: next tick we re-check the hotbar and advance once the swap lands
			return;
		}

		//no bottle anywhere
		notify("No Ominous Bottles found");
		if (this.autoDisconnect.getValue()) {
			disconnect();
		}
		resetSequence();
	}

	private void selectSlot(int slot) {
		if (slot < 0 || slot > 8) {
			return;
		}
		mc.player.getInventory().setSelectedSlot(slot);
		if (mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
		}
	}

	private void disconnect() {
		notify("Out of Ominous Bottles - disconnecting");

		//stop the sequence now so we don't keep acting on a tearing-down world
		resetSequence();

		final Component reason = Component.literal("[AutoRaid] Out of Ominous Bottles");
		//run on the main thread at a safe point - disconnecting mid-tick can abort/throw
		mc.execute(() -> {
			if (mc.getConnection() != null) {
				mc.getConnection().getConnection().disconnect(reason);
			}
		});
	}

	private void resetSequence() {
		this.stage = 0;
		this.stageTicks = 0;
		this.delayCounter = 0;
		this.drinkTimer = 0;
		this.guardTicks = 0;
		//never leave the use key stuck down
		if (mc.options != null && mc.options.keyUse != null) {
			mc.options.keyUse.setDown(false);
		}
	}

	private void notify(String message) {
		if (this.notifications.getValue()) {
			ChatUtils.print("[AutoRaid] " + message);
		}
	}

	/**
	 * Whether the player already has an omen that makes drinking another bottle pointless.
	 */
	private boolean hasOmen() {
		return mc.player.hasEffect(MobEffects.BAD_OMEN) || mc.player.hasEffect(MobEffects.RAID_OMEN);
	}

	/**
	 * Whether a boss bar name represents a raid victory.
	 */
	private boolean isVictory(Component name) {
		if (name == null) {
			return false;
		}
		if (containsVictoryKey(name)) {
			return true;
		}
		if (this.textDetection.getValue()) {
			return name.getString().toLowerCase(Locale.ROOT).contains("victory");
		}
		return false;
	}

	/**
	 * Recursively scans a component (and its siblings) for the raid victory translation key.
	 * Language-independent - matches "event.minecraft.raid.victory" / ".victory.full".
	 */
	private static boolean containsVictoryKey(Component component) {
		final ComponentContents contents = component.getContents();
		if (contents instanceof TranslatableContents translatable) {
			final String key = translatable.getKey();
			if (key != null && key.contains("raid.victory")) {
				return true;
			}
		}
		for (Component sibling : component.getSiblings()) {
			if (containsVictoryKey(sibling)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void onDisable() {
		this.victoryFlag = false;
		resetSequence();
	}
}
