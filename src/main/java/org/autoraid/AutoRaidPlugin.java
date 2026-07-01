package org.autoraid;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

/**
 * AutoRaid - automatically drinks an Ominous Bottle when the raid victory boss bar appears.
 *
 * @author eclipxia
 */
public class AutoRaidPlugin extends Plugin {

	@Override
	public void onLoad() {
		this.getLogger().info("AutoRaid loading...");

		final AutoRaidModule module = new AutoRaidModule();
		RusherHackAPI.getModuleManager().registerFeature(module);

		this.getLogger().info("AutoRaid loaded!");
	}

	@Override
	public void onUnload() {
		this.getLogger().info("AutoRaid unloaded!");
	}
}
