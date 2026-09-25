package dev.champions.client;

import com.internationalduck.championscode.module.exploit.ExampleExploitModule;
import com.internationalduck.championscode.module.payload.ExamplePayloadModule;
import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.client.java.JavaRuntimeManager;
import dev.champions.client.ui.ArmedTitleOverlay;
import dev.champions.client.ui.ChampionsCategoryGuiScreen;
import dev.champions.client.ui.ChampionsClickGuiScreen;
import dev.champions.client.ui.ChampionsConsoleScreen;
import dev.champions.client.ui.ChampionsJavaScreen;
import dev.champions.client.ui.ExploitWarningScreen;
import dev.champions.client.update.GitLabUpdateService;
import dev.champions.client.update.UpdateNotifier;
import dev.champions.client.update.UpdateSettings;
import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.update.UpdateService;
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
		Runnable openClickGui = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> client.setScreenAndShow(new ChampionsClickGuiScreen(console, new ChampionsConsoleScreen(console))));
		};
		Runnable openCategoryGui = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> client.setScreenAndShow(new ChampionsCategoryGuiScreen(console, new ChampionsConsoleScreen(console))));
		};
		Runnable closeUi = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> client.setScreenAndShow(null));
		};
		ExploitWarningPrompt exploitWarningPrompt = request -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> client.setScreenAndShow(ExploitWarningScreen.forRun(client.gui.screen(), request)));
		};
		UpdateSettings updateSettings = new UpdateSettings(configDirectory);
		UpdateService updateService = new GitLabUpdateService();
		console = new ChampionConsole(configDirectory, contextSupplier, chatRuntime, javaCommands,
			openClickGui, openCategoryGui, exploitWarningPrompt, updateService, closeUi);
		// Client-side drop-in modules (see docs/exploit-and-payload-files.md):
		// one console.registerModule(...) line per file dropped into the
		// exploit or payload folder.
		console.registerModule(new ExampleExploitModule());
		console.registerModule(new ExamplePayloadModule());
		javaRuntime = new JavaRuntimeManager(configDirectory, contextSupplier, console);
		javaRuntimeReference.set(javaRuntime);
		ClientReceiveMessageEvents.GAME.register(chatRuntime::onGameMessage);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> javaRuntime.close());
		new UpdateNotifier(updateService, updateSettings).register();

		KeyMapping.Category category = KeyMapping.Category.register(id("security_research"));
		KeyMapping openConsole = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.champions.open_console",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_F8,
			category
		));
		KeyMapping openJavaStudio = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.champions.open_java_studio",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_F7,
			category
		));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			chatRuntime.tick(client);
			PlayerMotionTelemetry.tick(client);
			SessionPlayers.capture(client);
			while (openConsole.consumeClick()) client.setScreenAndShow(new ChampionsConsoleScreen(console));
			while (openJavaStudio.consumeClick()) client.setScreenAndShow(new ChampionsJavaScreen(console, javaRuntime));
		});
		ArmedTitleOverlay.register();
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
		LOGGER.info("ChampionsCode {} initialized (environment: {}): defensive modules, " +
			"hash-gated runtime Java and update checks ready",
			version,
			FabricLoader.getInstance().getEnvironmentType());
		LOGGER.info("keybinds: F8 console, F7 Java Studio; commands: clickgui, categorygui, exit");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
