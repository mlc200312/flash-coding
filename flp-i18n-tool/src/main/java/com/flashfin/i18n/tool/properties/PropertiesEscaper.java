package com.flashfin.i18n.tool.properties;

public final class PropertiesEscaper {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PropertiesEscaper() {
    }

    public static String escapeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\f') {
                out.append("\\f");
            } else if (c < 0x20 || c > 0x7e) {
                appendUnicode(out, c);
            } else {
                out.append(c);
            }
        }
        if (out.length() > 0 && out.charAt(0) == ' ') {
            out.insert(0, '\\');
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.insert(out.length() - 1, '\\');
        }
        return out.toString();
    }

    public static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'u') {
                if (i + 4 < value.length()) {
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // fall through and keep the sequence literal
                    }
                }
                out.append('\\').append(next);
            } else if (next == 't') {
                out.append('\t');
            } else if (next == 'n') {
                out.append('\n');
            } else if (next == 'r') {
                out.append('\r');
            } else if (next == 'f') {
                out.append('\f');
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    public static boolean hasNonAscii(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7e) {
                return true;
            }
        }
        return false;
    }

    private static void appendUnicode(StringBuilder out, char c) {
        out.append('\\').append('u');
        out.append(HEX[(c >> 12) & 0xF]);
        out.append(HEX[(c >> 8) & 0xF]);
        out.append(HEX[(c >> 4) & 0xF]);
        out.append(HEX[c & 0xF]);
    }
}
