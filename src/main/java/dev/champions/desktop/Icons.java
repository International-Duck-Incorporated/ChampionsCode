package dev.champions.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Vector glyphs for the sidebar and cards.
 *
 * <p>Drawn with {@link Graphics2D} instead of shipped PNGs so they stay sharp at
 * any display scale and add nothing to the JAR. Every glyph is laid out in a
 * {@code size × size} box and stroked with a width proportional to {@code size}.</p>
 */
public final class Icons {
	public enum Glyph { BRAND, TERMINAL, MODULES, CODE, LOG, SETTINGS, PLAY, STOP, SAVE, PLUS, FOLDER, FILE, SHIELD }

	private Icons() {}

	/** Draws a glyph at {@code (x, y)} with the given box size and colour. */
	public static void paint(Graphics2D graphics, Glyph glyph, int x, int y, int size, Color colour) {
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.translate(x, y);
			float scale = size / 24f;
			g.scale(scale, scale);
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			switch (glyph) {
				case BRAND -> brand(g);
				case TERMINAL -> terminal(g);
				case MODULES -> modules(g);
				case CODE -> code(g);
				case LOG -> log(g);
				case SETTINGS -> settings(g);
				case PLAY -> play(g);
				case STOP -> stop(g);
				case SAVE -> save(g);
				case PLUS -> plus(g);
				case FOLDER -> folder(g);
				case FILE -> file(g);
				case SHIELD -> shield(g);
			}
		} finally {
			g.dispose();
		}
	}

	/** The project mark: a crimson {@code >_} prompt and a double chevron. */
	private static void brand(Graphics2D g) {
		Path2D prompt = new Path2D.Float();
		prompt.moveTo(3, 6);
		prompt.lineTo(8, 11);
		prompt.lineTo(3, 16);
		g.draw(prompt);
		g.drawLine(11, 16, 17, 16);
		Path2D chevrons = new Path2D.Float();
		chevrons.moveTo(15, 4);
		chevrons.lineTo(10, 12);
		chevrons.lineTo(15, 20);
		chevrons.moveTo(21, 4);
		chevrons.lineTo(16, 12);
		chevrons.lineTo(21, 20);
		g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(chevrons);
	}

	private static void terminal(Graphics2D g) {
		g.draw(new RoundRectangle2D.Float(2.5f, 4f, 19f, 16f, 4f, 4f));
		Path2D caret = new Path2D.Float();
		caret.moveTo(7, 10);
		caret.lineTo(10, 12.5);
		caret.lineTo(7, 15);
		g.draw(caret);
		g.draw(new java.awt.geom.Line2D.Float(12.5f, 15f, 17f, 15f));
	}

	private static void modules(Graphics2D g) {
		g.draw(new RoundRectangle2D.Float(3f, 3f, 7.5f, 7.5f, 2.5f, 2.5f));
		g.draw(new RoundRectangle2D.Float(13.5f, 3f, 7.5f, 7.5f, 2.5f, 2.5f));
		g.draw(new RoundRectangle2D.Float(3f, 13.5f, 7.5f, 7.5f, 2.5f, 2.5f));
		g.draw(new RoundRectangle2D.Float(13.5f, 13.5f, 7.5f, 7.5f, 2.5f, 2.5f));
	}

	private static void code(Graphics2D g) {
		Path2D left = new Path2D.Float();
		left.moveTo(9, 7);
		left.lineTo(4.5f, 12);
		left.lineTo(9, 17);
		Path2D right = new Path2D.Float();
		right.moveTo(15, 7);
		right.lineTo(19.5f, 12);
		right.lineTo(15, 17);
		g.draw(left);
		g.draw(right);
		g.drawLine(13, 5, 11, 19);
	}

	private static void log(Graphics2D g) {
		g.draw(new RoundRectangle2D.Float(3f, 4f, 18f, 16f, 3f, 3f));
		g.drawLine(6, 9, 15, 9);
		g.drawLine(6, 12, 18, 12);
		g.drawLine(6, 15, 12, 15);
	}

	private static void settings(Graphics2D g) {
		g.draw(new java.awt.geom.Ellipse2D.Float(8.5f, 8.5f, 7f, 7f));
		double[] angles = {0, 90, 180, 270};
		for (double degrees : angles) {
			double radians = Math.toRadians(degrees);
			float x1 = (float) (12 + Math.cos(radians) * 5.4);
			float y1 = (float) (12 + Math.sin(radians) * 5.4);
			float x2 = (float) (12 + Math.cos(radians) * 8.6);
			float y2 = (float) (12 + Math.sin(radians) * 8.6);
			g.drawLine(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
		}
	}

	private static void play(Graphics2D g) {
		Path2D triangle = new Path2D.Float();
		triangle.moveTo(8, 5.5);
		triangle.lineTo(18.5, 12);
		triangle.lineTo(8, 18.5);
		triangle.closePath();
		g.fill(triangle);
	}

	private static void stop(Graphics2D g) {
		g.fill(new RoundRectangle2D.Float(6.5f, 6.5f, 11f, 11f, 2.5f, 2.5f));
	}

	private static void save(Graphics2D g) {
		Path2D floppy = new Path2D.Float();
		floppy.moveTo(4, 7);
		floppy.lineTo(4, 19);
		floppy.lineTo(20, 19);
		floppy.lineTo(20, 8.5f);
		floppy.lineTo(17, 5);
		floppy.lineTo(6.5f, 5);
		floppy.closePath();
		g.draw(floppy);
		g.drawLine(8, 5, 8, 10);
		g.drawLine(8, 10, 16, 10);
		g.draw(new RoundRectangle2D.Float(8f, 13f, 8f, 6f, 1.5f, 1.5f));
	}

	private static void plus(Graphics2D g) {
		g.drawLine(12, 5, 12, 19);
		g.drawLine(5, 12, 19, 12);
	}

	private static void folder(Graphics2D g) {
		Path2D folder = new Path2D.Float();
		folder.moveTo(3, 7);
		folder.lineTo(3, 18);
		folder.lineTo(21, 18);
		folder.lineTo(21, 9);
		folder.lineTo(11, 9);
		folder.lineTo(9, 6);
		folder.lineTo(3, 6);
		folder.closePath();
		g.draw(folder);
	}

	private static void file(Graphics2D g) {
		Path2D page = new Path2D.Float();
		page.moveTo(6, 3.5f);
		page.lineTo(14, 3.5f);
		page.lineTo(18.5f, 8);
		page.lineTo(18.5f, 20.5f);
		page.lineTo(6, 20.5f);
		page.closePath();
		g.draw(page);
		g.draw(new java.awt.geom.Line2D.Float(14f, 3.5f, 14f, 8f));
		g.draw(new java.awt.geom.Line2D.Float(14f, 8f, 18.5f, 8f));
	}

	private static void shield(Graphics2D g) {
		Path2D shield = new Path2D.Float();
		shield.moveTo(12, 3.5f);
		shield.lineTo(20, 6.5f);
		shield.lineTo(20, 12);
		shield.curveTo(20, 17, 16, 20, 12, 21);
		shield.curveTo(8, 20, 4, 17, 4, 12);
		shield.lineTo(4, 6.5f);
		shield.closePath();
		g.draw(shield);
		Path2D check = new Path2D.Float();
		check.moveTo(8.5f, 12);
		check.lineTo(11, 14.5f);
		check.lineTo(15.5f, 9.5f);
		g.draw(check);
	}
}
