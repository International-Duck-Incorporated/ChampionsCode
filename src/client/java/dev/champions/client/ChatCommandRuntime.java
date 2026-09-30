package dev.champions.client;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.champions.core.module.CommandSafetyPolicy;
import dev.champions.core.module.active.ActiveModuleRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Client-thread implementation of bounded, command-tree-validated chat probes. */
final class ChatCommandRuntime implements ActiveModuleRuntime {
	private static final int IDLE_TICKS_AFTER_MESSAGE = 10;
	private static final int MINIMUM_TICKS_BETWEEN_PROBES = 10;
	private long tick;
	private long lastSendTick = Long.MIN_VALUE / 2;
	private Pending pending;

	@Override
	public synchronized CompletionStage<ChatTranscript> execute(ChatCommandRequest request) {
		if (pending != null) return completed(request, Status.BUSY, "Another chat capture is already active.");
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener listener = client.getConnection();
		if (listener == null || !listener.isAcceptingMessages()) {
			return completed(request, Status.DISCONNECTED, "No active server connection can accept a command.");
		}
		String rejection = validateAgainstLiveTree(listener, request);
		if (rejection != null) return completed(request, Status.REJECTED, rejection);

		long earliestSend = Math.max(tick + 1, lastSendTick + MINIMUM_TICKS_BETWEEN_PROBES);
		Pending next = new Pending(request, earliestSend);
		pending = next;
		return next.future;
	}

	synchronized void onGameMessage(Component message, boolean overlay) {
		if (pending == null || !pending.sent || overlay) return;
		String text = sanitize(message.getString());
		if (text.isBlank()) return;
		pending.messages.add(text);
		pending.idleDeadline = tick + IDLE_TICKS_AFTER_MESSAGE;
		if (pending.messages.size() >= pending.request.maxMessages()) complete(Status.COMPLETED, "Message limit reached.");
	}

	synchronized void tick(Minecraft client) {
		tick++;
		if (pending == null) return;
		ClientPacketListener listener = client.getConnection();
		if (listener == null || !listener.isAcceptingMessages()) {
			complete(Status.DISCONNECTED, "Connection ended during capture.");
			return;
		}
		if (!pending.sent && tick >= pending.earliestSendTick) {
			String rejection = validateAgainstLiveTree(listener, pending.request);
			if (rejection != null) {
				complete(Status.REJECTED, rejection);
				return;
			}
			try {
				listener.sendCommand(pending.request.wireCommand());
				pending.sent = true;
				pending.deadline = tick + pending.request.timeoutTicks();
				lastSendTick = tick;
			} catch (RuntimeException error) {
				complete(Status.ERROR, safeMessage(error));
			}
			return;
		}
		if (!pending.sent) return;
		if (!pending.messages.isEmpty() && tick >= pending.idleDeadline) {
			complete(Status.COMPLETED, "Response capture became idle.");
		} else if (tick >= pending.deadline) {
			complete(Status.TIMED_OUT, "Bounded response window expired.");
		}
	}

	private static String validateAgainstLiveTree(ClientPacketListener listener, ChatCommandRequest request) {
		if (!CommandSafetyPolicy.mayProbe(request.root())) return "Command root is blocked by the safety policy.";
		CommandNode<?> root = listener.getCommands().getRoot().getChild(request.root());
		if (root == null) return "Command root is no longer advertised by the server.";
		return switch (request.variant()) {
			case BLANK -> root.getCommand() == null
				? null : "Blank probe rejected because the root is executable without arguments.";
			case VERSION, V -> {
				CommandNode<?> child = root.getChild(request.variant().suffix());
				yield child instanceof LiteralCommandNode<?> ? null
					: "Metadata suffix is no longer an advertised literal child.";
			}
			case PATH -> validateLiteralPath(root, request.literalPath());
		};
	}

	private static String validateLiteralPath(CommandNode<?> root, List<String> path) {
		CommandNode<?> current = root;
		for (String segment : path) {
			CommandNode<?> child = current.getChild(segment);
			if (!(child instanceof LiteralCommandNode<?>)) return "Probe path is no longer an advertised literal chain.";
			current = child;
		}
		return null;
	}

	private void complete(Status status, String detail) {
		Pending done = pending;
		if (done == null) return;
		pending = null;
		done.future.complete(new ChatTranscript(done.request, List.copyOf(done.messages), status, detail));
	}

	private static CompletionStage<ChatTranscript> completed(ChatCommandRequest request, Status status, String detail) {
		return CompletableFuture.completedFuture(new ChatTranscript(request, List.of(), status, detail));
	}

	private static String sanitize(String value) {
		String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
		return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500) + "…";
	}

	private static String safeMessage(Throwable error) {
		String value = error.getMessage();
		return value == null || value.isBlank() ? error.getClass().getSimpleName() : sanitize(value);
	}

	private static final class Pending {
		private final ChatCommandRequest request;
		private final long earliestSendTick;
		private final List<String> messages = new ArrayList<>();
		private final CompletableFuture<ChatTranscript> future = new CompletableFuture<>();
		private boolean sent;
		private long deadline = Long.MAX_VALUE;
		private long idleDeadline = Long.MAX_VALUE;

		private Pending(ChatCommandRequest request, long earliestSendTick) {
			this.request = request;
			this.earliestSendTick = earliestSendTick;
		}
	}
}
