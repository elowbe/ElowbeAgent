package com.elowbe.main;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import com.jinteractive.main.Colors;

import lib.console.main.JinGraphics;
import lib.console.util.ColorData;
import lib.console.util.ColorExtractor;
import lib.console.util.Pair;
import lib.console.widgets.Widget;

public class PrintWidget extends Widget {
	private final List<List<Cell>> lines = new ArrayList<>();
	private List<List<Cell>> wrappedCache = new ArrayList<>();
	private int cachedWidth = -1;
	private int cachedVersion = -1;
	private int version;
	private int scroll;
	private int tabSize = 4;
	private boolean autoScroll = true;
	private Color defaultColor = Colors.white;
	private Color currentColor = Colors.white;

	private static class Cell {
		private final char value;
		private final Color color;

		private Cell(char value, Color color) {
			this.value = value;
			this.color = color;
		}
	}

	public PrintWidget(int column, int row, int width, int height) {
		super(column, row, width, height);
		clear();
	}

	@Override
	public void init() {
	}

	@Override
	public void tick(float delta) {
		clampScroll();
	}

	@Override
	public synchronized void draw(JinGraphics t2d) {
		if (width <= 0 || height <= 0) {
			return;
		}

		clampScroll();
		List<List<Cell>> wrappedLines = getWrappedLines();
		int end = Math.min(wrappedLines.size(), scroll + height);
		Color oldColor = t2d.getColor();

		for (int rowIndex = scroll; rowIndex < end; rowIndex++) {
			List<Cell> line = wrappedLines.get(rowIndex);
			int y = rowIndex - scroll;
			int endColumn = Math.min(width, line.size());

			for (int x = 0; x < endColumn; x++) {
				Cell cell = line.get(x);
				t2d.setColor(cell.color);
				t2d.drawChar(cell.value, x, y);
			}
		}

		t2d.setColor(oldColor);
		t2d.setHighlight(false);
	}

	public synchronized void print(Object value) {
		print(String.valueOf(value));
	}

	public synchronized void print(char value) {
		print(String.valueOf(value));
	}

	public synchronized void print(String text) {
		append(text);
		if (autoScroll) {
			scrollToBottom();
		}
	}

	public synchronized void println() {
		newLine();
		if (autoScroll) {
			scrollToBottom();
		}
	}

	public synchronized void println(Object value) {
		println(String.valueOf(value));
	}

	public synchronized void println(char value) {
		println(String.valueOf(value));
	}

	public synchronized void println(String text) {
		append(text);
		newLine();
		if (autoScroll) {
			scrollToBottom();
		}
	}

	public synchronized void clear() {
		lines.clear();
		lines.add(new ArrayList<>());
		scroll = 0;
		autoScroll = true;
		invalidateWrap();
	}

	public synchronized void setColor(Color color) {
		currentColor = color == null ? defaultColor : color;
	}

	public synchronized Color getColor() {
		return currentColor;
	}

	public synchronized void setDefaultColor(Color color) {
		defaultColor = color == null ? Colors.white : color;
	}

	public synchronized Color getDefaultColor() {
		return defaultColor;
	}

	public synchronized void resetColor() {
		currentColor = defaultColor;
	}

	public synchronized int getScroll() {
		clampScroll();
		return scroll;
	}

	public synchronized int getMaxScroll() {
		return Math.max(0, getWrappedLines().size() - Math.max(0, height));
	}

	public synchronized int getLineCount() {
		return getWrappedLines().size();
	}

	public synchronized int getTabSize() {
		return tabSize;
	}

	public synchronized void setTabSize(int tabSize) {
		this.tabSize = Math.max(1, tabSize);
	}

	public synchronized void scrollToTop() {
		scroll = 0;
		autoScroll = false;
	}

	public synchronized void scrollToBottom() {
		scroll = getMaxScroll();
		autoScroll = true;
	}

	@Override
	public synchronized void scroll(int amount) {
		scroll += amount;
		clampScroll();
		autoScroll = scroll == getMaxScroll();
	}

	private void append(String text) {
		if (text == null) {
			text = "null";
		}

		text = text.replace("\r\n", "\n").replace('\r', '\n');
		Pair<String, List<ColorData>> result = ColorExtractor.extractColorData(text);
		String cleanText = result.first;
		List<ColorData> colorData = result.second;
		int colorIndex = 0;

		for (int i = 0; i < cleanText.length(); i++) {
			while (colorIndex < colorData.size() && colorData.get(colorIndex).getIndex() == i) {
				currentColor = Colors.hex(colorData.get(colorIndex).getColor());
				colorIndex++;
			}

			char character = cleanText.charAt(i);
			if (character == '\n') {
				newLine();
			} else if (character == '\t') {
				appendTab();
			} else if (character == '\b') {
				backspace();
			} else if (!Character.isISOControl(character)) {
				currentLine().add(new Cell(character, currentColor));
			}
		}

		while (colorIndex < colorData.size() && colorData.get(colorIndex).getIndex() == cleanText.length()) {
			currentColor = Colors.hex(colorData.get(colorIndex).getColor());
			colorIndex++;
		}

		invalidateWrap();
	}

	private void appendTab() {
		int spaces = tabSize - (currentLine().size() % tabSize);
		for (int i = 0; i < spaces; i++) {
			currentLine().add(new Cell(' ', currentColor));
		}
	}

	private void backspace() {
		List<Cell> line = currentLine();
		if (!line.isEmpty()) {
			line.remove(line.size() - 1);
			return;
		}

		if (lines.size() > 1) {
			lines.remove(lines.size() - 1);
		}
	}

	private void newLine() {
		lines.add(new ArrayList<>());
		invalidateWrap();
	}

	private List<Cell> currentLine() {
		if (lines.isEmpty()) {
			lines.add(new ArrayList<>());
		}
		return lines.get(lines.size() - 1);
	}

	private List<List<Cell>> getWrappedLines() {
		int wrapWidth = Math.max(1, width);
		if (cachedWidth == wrapWidth && cachedVersion == version) {
			return wrappedCache;
		}

		List<List<Cell>> wrappedLines = new ArrayList<>();
		for (List<Cell> line : lines) {
			wrapLine(line, wrapWidth, wrappedLines);
		}

		if (wrappedLines.isEmpty()) {
			wrappedLines.add(new ArrayList<>());
		}

		wrappedCache = wrappedLines;
		cachedWidth = wrapWidth;
		cachedVersion = version;
		return wrappedCache;
	}

	private void wrapLine(List<Cell> line, int wrapWidth, List<List<Cell>> wrappedLines) {
		if (line.isEmpty()) {
			wrappedLines.add(new ArrayList<>());
			return;
		}

		List<Cell> current = new ArrayList<>();
		for (Cell cell : line) {
			current.add(cell);
			if (current.size() > wrapWidth) {
				current = wrapCurrentLine(current, wrapWidth, wrappedLines);
			}
		}

		wrappedLines.add(current);
	}

	private List<Cell> wrapCurrentLine(List<Cell> current, int wrapWidth, List<List<Cell>> wrappedLines) {
		int breakIndex = findLastSpace(current, wrapWidth);
		if (breakIndex > 0) {
			wrappedLines.add(new ArrayList<>(current.subList(0, breakIndex)));
			return new ArrayList<>(current.subList(breakIndex + 1, current.size()));
		}

		wrappedLines.add(new ArrayList<>(current.subList(0, wrapWidth)));
		return new ArrayList<>(current.subList(wrapWidth, current.size()));
	}

	private int findLastSpace(List<Cell> line, int wrapWidth) {
		int end = Math.min(wrapWidth, line.size() - 1);
		for (int i = end; i >= 0; i--) {
			if (line.get(i).value == ' ') {
				return i;
			}
		}
		return -1;
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, getMaxScroll()));
	}

	private void invalidateWrap() {
		version++;
		cachedVersion = -1;
	}

	@Override
	public void render(Graphics2D g2d) {
	}

	@Override
	public void destroy() {
	}

	@Override
	public void keyDown(KeyEvent e) {
	}

	@Override
	public void keyUp(KeyEvent e) {
	}
}
