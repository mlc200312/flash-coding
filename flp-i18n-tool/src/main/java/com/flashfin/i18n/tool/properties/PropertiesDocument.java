package com.flashfin.i18n.tool.properties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-preserving .properties document. Comments, blank lines and line order are
 * kept byte-for-byte unless an entry is explicitly changed.
 */
public final class PropertiesDocument {

    private final String originalContent;
    private final String lineSeparator;
    private final List<Line> lines;
    private final Map<String, Line> lastByKey;
    private boolean dirty;

    private PropertiesDocument(String originalContent, String lineSeparator, List<Line> lines) {
        this.originalContent = originalContent;
        this.lineSeparator = lineSeparator;
        this.lines = lines;
        this.lastByKey = new LinkedHashMap<String, Line>();
        for (Line line : lines) {
            if (line.isEntry()) {
                lastByKey.put(line.key, line);
            }
        }
    }

    public static PropertiesDocument empty() {
        return new PropertiesDocument("", "\n", new ArrayList<Line>());
    }

    public static PropertiesDocument parse(String content) {
        String value = content == null ? "" : content;
        String separator = value.contains("\r\n") ? "\r\n" : "\n";
        List<Line> parsed = new ArrayList<Line>();
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('\n', start);
            String raw;
            if (end < 0) {
                raw = value.substring(start);
                start = value.length();
            } else {
                raw = value.substring(start, end);
                if (raw.endsWith("\r")) {
                    raw = raw.substring(0, raw.length() - 1);
                }
                start = end + 1;
            }
            parsed.add(Line.parse(raw));
        }
        return new PropertiesDocument(value, separator, parsed);
    }

    public String getLineSeparator() {
        return lineSeparator;
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean hasKey(String key) {
        return key != null && lastByKey.containsKey(key);
    }

    public boolean isCommented(String key) {
        Line line = lastByKey.get(key);
        return line != null && line.comment;
    }

    public String getActiveValue(String key) {
        Line line = lastByKey.get(key);
        if (line == null || line.comment) {
            return null;
        }
        return line.value;
    }

    /**
     * True when setting the value would change the file: missing key, commented
     * placeholder, different value, or a value still stored as raw non-ASCII.
     */
    public boolean needsValueUpdate(String key, String rawValue) {
        Line line = lastByKey.get(key);
        if (line == null || line.comment) {
            return true;
        }
        if (rawValue == null ? line.value != null : !rawValue.equals(line.value)) {
            return true;
        }
        return PropertiesEscaper.hasNonAscii(line.raw);
    }

    public String getAnyValue(String key) {
        Line line = lastByKey.get(key);
        return line == null ? null : line.value;
    }

    /**
     * Active values in file order. Later duplicate keys win, matching Properties.load.
     */
    public Map<String, String> getActiveValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Line line : lines) {
            if (line.isEntry() && !line.comment) {
                values.put(line.key, line.value);
            }
        }
        return values;
    }

    /**
     * Keys that appear as entries, in file order, including commented placeholder lines.
     */
    public List<String> getEntryKeys() {
        List<String> keys = new ArrayList<String>();
        for (Line line : lines) {
            if (line.isEntry() && !keys.contains(line.key)) {
                keys.add(line.key);
            }
        }
        return keys;
    }

    public void setValue(String key, String rawValue) {
        String value = rawValue == null ? "" : rawValue;
        Line line = lastByKey.get(key);
        if (line == null) {
            Line added = Line.active(key, value);
            lines.add(added);
            lastByKey.put(key, added);
            dirty = true;
            return;
        }
        boolean wasActive = !line.comment;
        boolean sameValue = value.equals(line.value);
        if (wasActive && sameValue && !PropertiesEscaper.hasNonAscii(line.raw)) {
            return;
        }
        line.comment = false;
        line.value = value;
        line.dirty = true;
        dirty = true;
    }

    public void addCommented(String key) {
        if (key == null || lastByKey.containsKey(key)) {
            return;
        }
        Line added = Line.commented(key);
        lines.add(added);
        lastByKey.put(key, added);
        dirty = true;
    }

    public String render() {
        if (!dirty) {
            return originalContent;
        }
        StringBuilder out = new StringBuilder(originalContent.length() + 64);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (i > 0) {
                out.append(lineSeparator);
            }
            out.append(line.render());
        }
        if (out.length() > 0) {
            out.append(lineSeparator);
        }
        return out.toString();
    }

    private static final class Line {

        private final String raw;
        private final String key;
        private String value;
        private boolean comment;
        private boolean dirty;

        private Line(String raw, String key, String value, boolean comment) {
            this.raw = raw;
            this.key = key;
            this.value = value;
            this.comment = comment;
        }

        private static Line parse(String raw) {
            String left = trimLeft(raw);
            if (left.isEmpty()) {
                return new Line(raw, null, null, false);
            }
            char first = left.charAt(0);
            if (first == '#' || first == '!') {
                Entry entry = parseEntry(trimLeft(left.substring(1)), true);
                if (entry == null) {
                    return new Line(raw, null, null, true);
                }
                return new Line(raw, entry.key, entry.value, true);
            }
            Entry entry = parseEntry(left, false);
            if (entry == null) {
                return new Line(raw, null, null, false);
            }
            return new Line(raw, entry.key, entry.value, false);
        }

        private static Line active(String key, String value) {
            Line line = new Line(null, key, value, false);
            line.dirty = true;
            return line;
        }

        private static Line commented(String key) {
            Line line = new Line(null, key, "", true);
            line.dirty = true;
            return line;
        }

        private boolean isEntry() {
            return key != null;
        }

        private String render() {
            if (!dirty || !isEntry()) {
                return raw;
            }
            StringBuilder out = new StringBuilder();
            if (comment) {
                out.append('#');
            }
            out.append(key).append('=').append(PropertiesEscaper.escapeValue(value));
            return out.toString();
        }

        private static Entry parseEntry(String text, boolean explicitSeparatorOnly) {
            int length = text.length();
            int sep = -1;
            char sepChar = 0;
            for (int i = 0; i < length; i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '=' || (!explicitSeparatorOnly && c == ':')) {
                    sep = i;
                    sepChar = c;
                    break;
                }
                if (!explicitSeparatorOnly && isSpace(c)) {
                    sep = i;
                    sepChar = ' ';
                    break;
                }
            }
            if (sep <= 0) {
                return null;
            }
            String key = PropertiesEscaper.unescape(text.substring(0, sep));
            if (key.isEmpty() || containsSpace(key)) {
                return null;
            }
            int pos = sep + 1;
            if (sepChar == ' ') {
                while (pos < length && isSpace(text.charAt(pos))) {
                    pos++;
                }
                if (pos < length && (text.charAt(pos) == '=' || text.charAt(pos) == ':')) {
                    pos++;
                }
            }
            while (pos < length && isSpace(text.charAt(pos))) {
                pos++;
            }
            String value = pos >= length ? "" : text.substring(pos);
            return new Entry(key, PropertiesEscaper.unescape(value));
        }

        private static boolean containsSpace(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (isSpace(value.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\t' || c == '\f';
        }

        private static String trimLeft(String value) {
            int start = 0;
            while (start < value.length() && isSpace(value.charAt(start))) {
                start++;
            }
            return value.substring(start);
        }
    }

    private static final class Entry {

        private final String key;
        private final String value;

        private Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
