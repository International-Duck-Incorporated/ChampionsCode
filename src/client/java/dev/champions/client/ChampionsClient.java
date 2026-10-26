package dev.champions.client;

import com.internationalduck.championscode.module.exploit.ExampleExploitModule;
import com.internationalduck.championscode.module.payload.ExamplePayloadModule;
import com.internationalduck.championscode.module.audit.InstalledModsModule;
import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.client.desktop.DesktopRuntime;
import dev.champions.client.java.JavaRuntimeManager;
import dev.champions.client.ui.ArmedTitleOverlay;
import dev.champions.client.ui.ExploitWarningScreen;
import dev.champions.client.ui.ChampionsWorkspaceScreen;
import dev.champions.client.ui.PlayerIdentityInfoScreen;
import dev.champions.client.update.GitLabUpdateService;
import dev.champions.client.update.UpdateNotifier;
import dev.champions.client.update.UpdateSettings;
import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.module.net.PlayerIdentityEvents;
import dev.champions.core.update.UpdateService;
import net.fabricmc.api.ClientModInitializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
	private DesktopRuntime desktop;
	private KeyMapping openConsoleKey;
	private KeyMapping openJavaStudioKey;
	private KeyMapping openLogViewerKey;

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
		// All UIs open as windows of one shared workspace, so opening one never
		// closes another (see ChampionsScreens).
		Runnable openClickGui = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> {
				if (desktop != null) desktop.show("modules", () -> ChampionsScreens.open(client, console, () -> javaRuntime,
					openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.CLICKGUI));
				else ChampionsScreens.open(client, console, () -> javaRuntime,
					openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.CLICKGUI);
			});
		};
		Runnable openCategoryGui = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> ChampionsScreens.open(client, console, () -> javaRuntime,
				openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.CATEGORYGUI));
		};
		Runnable openLogViewer = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> ChampionsScreens.open(client, console, () -> javaRuntime,
				openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.LOGVIEWER));
		};
		Runnable closeUi = () -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> ChampionsScreens.closeTerminal(client));
		};
		ExploitWarningPrompt inGameWarning = request -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> client.setScreenAndShow(ExploitWarningScreen.forRun(client.gui.screen(), request)));
		};
		// The external app shows the same legal warning; the in-game screen takes it
		// over whenever no desktop session is attached.
		ExploitWarningPrompt exploitWarningPrompt = request -> {
			DesktopRuntime runtime = desktop;
			if (runtime != null && runtime.offerExploitPrompt(request)) return;
			inGameWarning.request(request);
		};
		UpdateSettings updateSettings = new UpdateSettings(configDirectory);
		UpdateService updateService = new GitLabUpdateService();
		console = new ChampionConsole(configDirectory, contextSupplier, chatRuntime, javaCommands,
			openClickGui, openCategoryGui, openLogViewer, exploitWarningPrompt, updateService, closeUi);
		PlayerIdentityEvents.setListener(details -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> {
				// A background terminal lookup must never interrupt gameplay. Show the
				// compact identity card only while the ChampionsCode workspace is open.
				if (client.gui.screen() instanceof ChampionsWorkspaceScreen workspace) {
					client.setScreenAndShow(new PlayerIdentityInfoScreen(workspace, details));
				}
			});
		});
		// Client-side drop-in modules (see docs/exploit-and-payload-files.md):
		// one console.registerModule(...) line per file dropped into the
		// exploit or payload folder.
		console.registerModule(new ExampleExploitModule());
		console.registerModule(new ExamplePayloadModule());
		// Mod-side inventory. Reads the loader and the channel telemetry; sends nothing.
		console.registerModule(new InstalledModsModule());
		javaRuntime = new JavaRuntimeManager(configDirectory, contextSupplier, console);
		javaRuntimeReference.set(javaRuntime);
		// `repo install` drops the verified source into the same plug-and-play
		// folder a hand-copied module goes in, then asks the runtime to reload.
		console.setModuleRepository(new dev.champions.core.repo.RepoConfig(configDirectory),
			(id, fileName, source) -> {
				try {
					Path folder = javaRuntime.moduleFolder();
					Files.createDirectories(folder);
					Files.writeString(folder.resolve(fileName), source, StandardCharsets.UTF_8);
					javaRuntime.submit("modules reload");
					return dev.champions.core.repo.ModuleSourceInstaller.Outcome.ok(
						"Installed " + fileName + " into " + folder + "; reloading the module folder.");
				} catch (IOException | RuntimeException error) {
					return dev.champions.core.repo.ModuleSourceInstaller.Outcome.failed(
						"Could not install " + fileName + ": " + error.getMessage());
				}
			});
		String modVersion = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
		// What this build can do. The reference build for Minecraft 26.3 has every
		// feature; a per-version adapter compiled for another Minecraft version
		// declares a smaller set and the console refuses the modules that need it.
		console.setCapabilitiesSupplier(() -> dev.champions.core.mc.ClientCapabilities.full(
			"26.3",
			FabricLoader.getInstance().getModContainer("fabricloader")
				.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown"),
			modVersion,
			"mc26.3"));
		desktop = new DesktopRuntime(configDirectory, console, contextSupplier,
			dev.champions.client.SessionPlayers::names, () -> modVersion,
			runnable -> Minecraft.getInstance().execute(runnable));
		// The chat command `interfaces` edits the same desktop.properties the
		// Settings tab writes; re-read it and start the bridge if it was off.
		console.setInterfacePreferences(new dev.champions.client.desktop.ChatInterfacePreferences(
			desktop, () -> {
				if (desktop.settings().usesExternal()) desktop.start();
			}));
		desktop.start();
		LOGGER.info("interface mode {} (settings: {}); external app attached: {}",
			desktop.settings().mode(), desktop.settings().file(), desktop.isAttached());
		ClientReceiveMessageEvents.GAME.register(chatRuntime::onGameMessage);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			PlayerIdentityEvents.clearListener();
			javaRuntime.close();
			if (desktop != null) desktop.close();
		});
		// Plug-and-play module folder: the runtime polls it while the game runs, so
		// dropping a .java file below config/champions-mod/modules/ is enough.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			JavaRuntimeManager runtime = javaRuntimeReference.get();
			if (runtime != null) runtime.tick();
		});
		new UpdateNotifier(updateService, updateSettings).register();

		KeyMapping.Category category = KeyMapping.Category.register(id("security_research"));
		openConsoleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.champions.open_console",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_F8,
			category
		));
		openJavaStudioKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.champions.open_java_studio",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_F7,
			category
		));
		openLogViewerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.champions.open_log_viewer",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_F6,
			category
		));
		// Network probes are dispatched at the start of the client tick, before
		// the normal movement flush. UI polling and passive sampling stay at END.
		ClientTickEvents.START_CLIENT_TICK.register(chatRuntime::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			PlayerMotionTelemetry.tick(client);
			SessionPlayers.capture(client);
			while (openConsoleKey.consumeClick()) {
				if (desktop != null) desktop.show("terminal", () -> ChampionsScreens.open(client, console, () -> javaRuntime,
					openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.TERMINAL));
				else ChampionsScreens.open(client, console, () -> javaRuntime,
					openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.TERMINAL);
			}
			while (openJavaStudioKey.consumeClick()) {
				if (desktop != null) desktop.show("codespace", () -> ChampionsScreens.open(client, console, () -> javaRuntime,
					openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.JAVASTUDIO));
				else ChampionsScreens.open(client, console, () -> javaRuntime,
					openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.JAVASTUDIO);
			}
			while (openLogViewerKey.consumeClick()) ChampionsScreens.open(client, console, () -> javaRuntime,
				openConsoleKey, openJavaStudioKey, openLogViewerKey, ChampionsScreens.LOGVIEWER);
		});
		ArmedTitleOverlay.register();
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
		LOGGER.info("ChampionsCode {} initialized (environment: {}): defensive modules, " +
			"plug-and-play drop-in modules, runtime Java and update checks ready",
			version,
			FabricLoader.getInstance().getEnvironmentType());
		LOGGER.info("drop-in module folder (searched recursively): {} - .java files here are compiled and registered automatically",
			javaRuntime.moduleFolder());
		LOGGER.info("keybinds: F8 console, F7 Java Studio, F6 log viewer (toggle their window while the workspace is open); commands: clickgui, categorygui, logs, java, interfaces (both|external|in-game), exit");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
