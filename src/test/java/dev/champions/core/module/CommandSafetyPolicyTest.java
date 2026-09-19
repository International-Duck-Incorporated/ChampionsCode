package dev.champions.core.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSafetyPolicyTest {
	@Test void filtersVanillaImplementationAndHazardousRoots() {
		assertFalse(CommandSafetyPolicy.isNonDefaultCandidate("minecraft:give"));
		assertFalse(CommandSafetyPolicy.isNonDefaultCandidate("plugins"));
		assertFalse(CommandSafetyPolicy.mayProbe("admin:sudo"));
		assertTrue(CommandSafetyPolicy.mayProbe("luckperms"));
		assertTrue(CommandSafetyPolicy.mayProbe("worldedit:worldedit"));
	}
}
