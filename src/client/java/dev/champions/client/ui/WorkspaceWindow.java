package dev.champions.client.ui;

/**
 * Geometry probe the workspace uses to route mouse input: a window answers
 * whether a screen-space point falls on any of its visible surfaces. Windows
 * decide their own shape (a console is one rectangle, the category GUI is a
 * set of panels).
 */
public interface WorkspaceWindow {
	boolean coversPoint(double mouseX, double mouseY);
}
