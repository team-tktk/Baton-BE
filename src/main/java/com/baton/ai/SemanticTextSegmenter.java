package com.baton.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits text without crossing pages/headings and keeps tables or procedures together where practical. */
final class SemanticTextSegmenter {
	private static final int TARGET_CHARS = 4_000;
	private static final Pattern SECTION_HEADING = Pattern.compile(
			"(?m)^(?:#{1,6}\\s+.+|제\\s*\\d+\\s*조(?:\\s*\\([^\\n]+\\))?|\\d+(?:\\.\\d+)+[.)]?\\s+[^\\n]{1,100})$");
	private static final Pattern LINE = Pattern.compile("(?m)^.*(?:\\R|$)");
	private static final Pattern PROCEDURE_LINE = Pattern.compile(
			"^\\s*(?:[-*•]|\\d+[.)]|[가-힣][.)]|(?:단계|Step)\\s*\\d+)\\s+.+$", Pattern.CASE_INSENSITIVE);

	private SemanticTextSegmenter() { }

	static List<Segment> split(String text, List<Integer> hardBoundaries) {
		if (text == null || text.isBlank()) return List.of();
		List<Integer> boundaries = new ArrayList<>(hardBoundaries == null ? List.of() : hardBoundaries);
		boundaries.add(0);
		boundaries.add(text.length());
		Matcher heading = SECTION_HEADING.matcher(text);
		while (heading.find()) boundaries.add(heading.start());
		Collections.sort(boundaries);
		boundaries = boundaries.stream().filter(value -> value >= 0 && value <= text.length()).distinct().toList();

		List<Segment> result = new ArrayList<>();
		for (int index = 0; index < boundaries.size() - 1; index++) {
			int start = boundaries.get(index);
			int end = boundaries.get(index + 1);
			if (start >= end || text.substring(start, end).isBlank()) continue;
			pack(text, blocks(text, start, end), result);
		}
		return result;
	}

	private static List<Block> blocks(String text, int start, int end) {
		List<Block> blocks = new ArrayList<>();
		Matcher lines = LINE.matcher(text);
		lines.region(start, end);
		Block current = null;
		while (lines.find()) {
			String line = lines.group().strip();
			if (line.isBlank()) continue;
			BlockType type = lineType(line);
			if (current != null && current.type() == type) {
				current = new Block(current.start(), lines.end(), type);
			} else {
				if (current != null) blocks.add(current);
				current = new Block(lines.start(), lines.end(), type);
			}
		}
		if (current != null) blocks.add(current);
		return blocks;
	}

	private static BlockType lineType(String line) {
		if (tableLine(line)) return BlockType.TABLE;
		if (PROCEDURE_LINE.matcher(line).matches()) return BlockType.PROCEDURE;
		return BlockType.TEXT;
	}

	private static boolean tableLine(String line) {
		return line.indexOf('\t') >= 0 || line.chars().filter(ch -> ch == '|').count() >= 2;
	}

	private static void pack(String text, List<Block> blocks, List<Segment> result) {
		Block current = null;
		for (Block next : blocks) {
			if (current == null) {
				current = next;
				continue;
			}
			boolean compatible = current.type() == next.type()
					|| current.type() == BlockType.TEXT && next.type() == BlockType.TEXT;
			if (compatible && next.end() - current.start() <= TARGET_CHARS) {
				current = new Block(current.start(), next.end(), current.type());
			} else {
				add(text, current, result);
				current = next;
			}
		}
		if (current != null) add(text, current, result);
	}

	private static void add(String text, Block block, List<Segment> result) {
		result.add(new Segment(block.start(), block.end(), headingAt(text, block.start()), block.type().name()));
	}

	static String headingAt(String text, int offset) {
		Matcher matcher = SECTION_HEADING.matcher(text);
		String latest = null;
		while (matcher.find() && matcher.start() <= offset) latest = matcher.group().trim();
		return latest;
	}

	private enum BlockType { TEXT, TABLE, PROCEDURE }
	private record Block(int start, int end, BlockType type) { }
	record Segment(int start, int end, String heading, String blockType) { }
}
