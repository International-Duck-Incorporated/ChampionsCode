package dev.champions.client.ui;

import net.minecraft.client.gui.screens.Screen;

/**
 * Seam that lets the individual ChampionsCode screens operate unchanged when
 * they run standalone and cooperate when they are embedded as windows in
 * {@link ChampionsWorkspaceScreen}. A hosted screen calls back through this
 * interface instead of swapping screens itself; when no host is set the
 * screen keeps its original standalone behavior.
 */
public interface WorkspaceHost {
	/** Opens (or focuses) the window with the given id, e.g. "terminal". */
	void requestOpen(String windowId);

	/** Closes the window with the given id; closes the workspace when it was the last one. */
	void requestClose(String windowId);

	/** The hosting screen, used as parent for dialogs spawned by windows. */
	Screen self();
}
