package com.flashfin.i18n.tool.dictionary;

import java.util.regex.Pattern;

public final class LanguageCodes {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private LanguageCodes() {
    }

    public static String canonical(String language) {
        if (language == null) {
            return null;
        }
        String value = language.trim();
        if ("zh".equals(value) || "zh-CN".equals(value) || "zh_CN".equals(value)) {
            return "zh_CN";
        }
        if ("en_ID".equals(value) || "en_id".equals(value)) {
            return "en_id";
        }
        return value;
    }

    public static String cdnPath(String language) throws DictionaryException {
        String canonical = canonical(language);
        if (canonical == null || canonical.isEmpty()) {
            throw new DictionaryException(DictionaryException.INVALID_LANGUAGE, "语言为空");
        }
        if ("zh_CN".equals(canonical)) {
            return "zh";
        }
        if ("en".equals(canonical) || "th".equals(canonical)) {
            return canonical;
        }
        if ("id".equals(canonical) || "en_id".equals(canonical)) {
            return "en_id";
        }
        if (!SAFE.matcher(canonical).matches()) {
            throw new DictionaryException(DictionaryException.INVALID_LANGUAGE,
                    "语言代码不合法: " + language);
        }
        return canonical;
    }

    public static String propertiesSuffix(String language) {
        String canonical = canonical(language);
        if ("en_id".equals(canonical)) {
            return "en_ID";
        }
        return canonical;
    }

    public static boolean isChinese(String language) {
        String canonical = canonical(language);
        return canonical != null && canonical.startsWith("zh");
    }

    /**
     * Translation-platform LangsDTO field. The platform has no en_id field; the
     * en_id dictionary is stored under {@code id}, matching the legacy pipeline.
     */
    public static String platformField(String language) throws DictionaryException {
        String canonical = canonical(language);
        if ("zh_CN".equals(canonical)) {
            return "zh";
        }
        if ("en_id".equals(canonical) || "id".equals(canonical)) {
            return "id";
        }
        if ("en".equals(canonical) || "th".equals(canonical)
                || "my".equals(canonical) || "vi".equals(canonical)) {
            return canonical;
        }
        throw new DictionaryException(DictionaryException.INVALID_LANGUAGE,
                "翻译平台不支持的语言: " + language);
    }
}
