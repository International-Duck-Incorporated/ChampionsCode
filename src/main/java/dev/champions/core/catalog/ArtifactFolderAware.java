package dev.champions.core.catalog;

/**
 * A module that writes an artifact needs the folder it may write into.
 *
 * <p>The console hands it over once, at construction, from its own config
 * directory. A module never resolves a path of its own, so "where artifacts go" has
 * exactly one answer and a ported module cannot quietly pick somewhere else.</p>
 */
@FunctionalInterface
public interface ArtifactFolderAware {
	void useAuditFolder(AuditFolder folder);
}
