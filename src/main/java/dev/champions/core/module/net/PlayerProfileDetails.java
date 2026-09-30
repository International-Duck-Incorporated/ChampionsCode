package dev.champions.core.module.net;

import java.util.List;
import java.util.Objects;

/** Combined public profile information shown by the client identity card. */
public record PlayerProfileDetails(
	MojangProfileClient.Identity identity,
	String playerDbAvatarUrl,
	String playerDbCachedAt,
	List<String> knownNames,
	String playerDbProfileUrl,
	String labyProfileUrl,
	String playerDbStatus
) {
	public PlayerProfileDetails {
		Objects.requireNonNull(identity, "identity");
		knownNames = knownNames == null ? List.of() : List.copyOf(knownNames);
		playerDbStatus = playerDbStatus == null ? "not queried" : playerDbStatus;
	}
}
