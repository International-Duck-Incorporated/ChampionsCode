package dev.champions.client.update;

import dev.champions.core.update.UpdateCheck;
import dev.champions.core.update.UpdateService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot startup check that announces newer releases in chat. It runs only
 * outside development environments, only when the player has joined a session,
 * fails silently when the feed is unreachable, and never sends anything.
 */
public final class UpdateNotifier {
	private static final Logger LOG = LoggerFactory.getLogger("champions-update");
	private final UpdateService updateService;
	private final UpdateSettings settings;
	private final AtomicBoolean done = new AtomicBoolean();

	public UpdateNotifier(UpdateService updateService, UpdateSettings settings) {
		this.updateService = updateService;
		this.settings = settings;
	}

	public void register() {
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (done.get() || client.player == null) return;
			if (!settings.checkOnStart()) {
				done.set(true);
				return;
			}
			if (!done.compareAndSet(false, true)) return;
			LOG.info("update check starting (feed: {})", "GitLab releases");
			updateService.checkLatest().whenComplete((check, error) -> {
				if (error != null) {
					LOG.warn("update check failed: {}", String.valueOf(error.getMessage()));
					return;
				}
				if (check.isEmpty()) {
					LOG.warn("update check returned no result");
					return;
				}
				UpdateCheck result = check.get();
				LOG.info("update check done: latest {}, newer available: {}",
					result.latestVersion(), result.newerAvailable());
				if (!result.newerAvailable()) return;
				client.execute(() -> client.gui.chatListener().handleSystemMessage(message(result), false));
			});
		});
	}

	private static Component message(UpdateCheck check) {
		return Component.literal("[ChampionsCode] ").withStyle(ChatFormatting.AQUA)
			.append(Component.literal("update available: ").withStyle(ChatFormatting.YELLOW))
			.append(Component.literal(check.latestVersion()).withStyle(ChatFormatting.GREEN)
				.withStyle(style -> style
					.withClickEvent(openReleasePage(check))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the GitLab release page")))))
			.append(Component.literal(" (running " + check.currentVersion() + "). Open the F8 console and run '"
				+ "update download " + check.latestVersion() + "'.").withStyle(ChatFormatting.GRAY));
	}

	private static ClickEvent openReleasePage(UpdateCheck check) {
		try {
			return new ClickEvent.OpenUrl(URI.create(check.releasePageUrl()));
		} catch (IllegalArgumentException invalidUrl) {
			return new ClickEvent.OpenUrl(URI.create("https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases"));
		}
	}
}
