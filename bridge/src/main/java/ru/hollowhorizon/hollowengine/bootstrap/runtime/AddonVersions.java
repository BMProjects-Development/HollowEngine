package ru.hollowhorizon.hollowengine.bootstrap.runtime;

import java.util.ArrayList;
import java.util.List;

public final class AddonVersions {
    private AddonVersions() {
    }

    /**
     * Orders versions the way releases are numbered: numbers compare as numbers, a text part such as
     * {@code beta} sorts below any number, so {@code 1.0.0-beta10} is older than {@code 1.0.0} and newer
     * than {@code 1.0.0-beta2}. Build metadata after {@code +} does not take part.
     */
    public static int compare(String left, String right) {
        List<String> leftParts = split(left);
        List<String> rightParts = split(right);
        int length = Math.max(leftParts.size(), rightParts.size());

        for (int index = 0; index < length; index++) {
            String leftPart = index < leftParts.size() ? leftParts.get(index) : "0";
            String rightPart = index < rightParts.size() ? rightParts.get(index) : "0";
            int result = compareSegment(leftPart, rightPart);
            if (result != 0) return result;
        }

        return 0;
    }

    /**
     * Which of two copies of one addon is used: the newer version, and between equal versions the one
     * from the source searched first. Negative when the left copy wins.
     */
    public static int compareCopies(String leftVersion, int leftPriority, String rightVersion, int rightPriority) {
        int byVersion = compare(rightVersion, leftVersion);
        return byVersion != 0 ? byVersion : Integer.compare(leftPriority, rightPriority);
    }

    private static int compareSegment(String left, String right) {
        Long leftNumber = number(left);
        Long rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) return Long.compare(leftNumber, rightNumber);
        if (leftNumber != null) return 1;
        if (rightNumber != null) return -1;
        return left.compareToIgnoreCase(right);
    }

    private static List<String> split(String version) {
        String trimmed = version == null ? "" : version.trim();
        int metadata = trimmed.indexOf('+');
        if (metadata >= 0) trimmed = trimmed.substring(0, metadata);

        List<String> parts = new ArrayList<>();
        for (String segment : trimmed.split("[._\\-]")) {
            if (segment.isEmpty()) continue;
            // "beta10" is the word and its number, so that it is not compared letter by letter with "beta2".
            int start = 0;
            for (int index = 1; index <= segment.length(); index++) {
                boolean boundary = index == segment.length() ||
                        Character.isDigit(segment.charAt(index)) != Character.isDigit(segment.charAt(index - 1));
                if (!boundary) continue;
                parts.add(segment.substring(start, index));
                start = index;
            }
        }
        if (parts.isEmpty()) parts.add("0");
        return parts;
    }

    private static Long number(String segment) {
        if (segment.isEmpty()) return null;
        for (int index = 0; index < segment.length(); index++) {
            if (!Character.isDigit(segment.charAt(index))) return null;
        }
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
