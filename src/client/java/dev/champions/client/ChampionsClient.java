package dev.champions.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.client.java.JavaRuntimeManager;
import dev.champions.client.ui.ChampionsConsoleScreen;
import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.console.ChampionConsole;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ChampionsClient implements ClientModInitializer {
	public static final String MOD_ID = "champions";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private ChampionConsole console;
	private JavaRuntimeManager javaRuntime;

	@Override
	public void onInitializeClient() {
		var configDirectory = FabricLoader.getInstance().getConfigDir().resolve("champions-mod");
		ChatCommandRuntime chatRuntime = new ChatCommandRuntime();
		Supplier<dev.champions.core.module.ModuleContext> contextSupplier = () -> MinecraftResearchContext.capture(Minecraft.getInstance());
		AtomicReference<JavaRuntimeManager> javaRuntimeReference = new AtomicReference<>();
		RuntimeJavaCommands javaCommands = command -> {
			JavaRuntimeManager manager = javaRuntimeReference.get();
			if (manager != null) manager.submit(command);
		};
		console = new ChampionConsole(configDirectory, contextSupplier, chatRuntime, javaCommands);
		javaRuntime = new JavaRuntimeManager(configDirectory, contextSupplier, console);
		javaRuntimeReference.set(javaRuntime);
		ClientReceiveMessageEvents.GAME.register(chatRuntime::onGameMessage);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> javaRuntime.close());

		KeyMapping.Category category = KeyMapping.Category.register(id("security_research"));
		KeyMapping openConsole = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.champions.open_console",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_F8,
			category
		));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			chatRuntime.tick(client);
			PlayerMotionTelemetry.tick(client);
			while (openConsole.consumeClick()) client.setScreenAndShow(new ChampionsConsoleScreen(console));
		});
		LOGGER.info("Champions Mod initialized: defensive modules and hash-gated runtime Java developer system ready");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
