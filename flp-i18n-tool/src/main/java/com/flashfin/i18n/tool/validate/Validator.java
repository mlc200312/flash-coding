package com.flashfin.i18n.tool.validate;

import com.flashfin.i18n.tool.messages.LocalMessages;
import com.flashfin.i18n.tool.scan.GeneratedKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Validator {

    private static final Pattern CODE_KEY = Pattern.compile("code_-?\\d+");
    private static final Pattern ENUM_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*_[^\\s=:]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*\\}");

    public ValidationResult validate(GeneratedKeys keys,
                                     Map<String, LocalMessages> localByLanguage,
                                     List<String> languages) {
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>();
        Map<String, String> types = keyTypes(keys);
        int keysChecked = types.size();

        for (Map.Entry<String, String> entry : types.entrySet()) {
            String key = entry.getKey();
            if (!matchesFormat(entry.getValue(), key)) {
                issues.add(new ValidationIssue(ValidationIssue.INVALID_KEY_FORMAT,
                        ValidationIssueSeverity.ERROR, key,
                        "key 格式不合法，类型为 " + entry.getValue(),
                        Collections.<String>emptyList(), null,
                        Collections.<String, String>emptyMap()));
            }
        }

        for (GeneratedKeys.DuplicateKey duplicate : keys.getDuplicates().values()) {
            issues.add(new ValidationIssue(ValidationIssue.DUPLICATE_KEY,
                    ValidationIssueSeverity.ERROR, duplicate.getKey(),
                    "重复 key: " + duplicate.getFirstSource() + " 与 " + duplicate.getDuplicateSource(),
                    Collections.<String>emptyList(), null,
                    Collections.<String, String>emptyMap()));
        }

        for (String key : types.keySet()) {
            List<String> emptyLanguages = new ArrayList<String>();
            for (String language : languages) {
                LocalMessages local = localByLanguage.get(language);
                String value = local == null ? null : local.get(key);
                if (isBlank(value)) {
                    emptyLanguages.add(language);
                }
            }
            if (!emptyLanguages.isEmpty()) {
                issues.add(new ValidationIssue(ValidationIssue.EMPTY_VALUE,
                        ValidationIssueSeverity.WARNING, key,
                        "以下语言缺少译文: " + join(emptyLanguages),
                        emptyLanguages, null, Collections.<String, String>emptyMap()));
            }
        }

        for (String key : types.keySet()) {
            Map<String, String> values = valuesByLanguage(key, localByLanguage, languages);
            if (values.size() < 2) {
                continue;
            }
            String referenceLanguage = referenceLanguage(values);
            List<String> expected = placeholders(values.get(referenceLanguage));
            Map<String, String> mismatched = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!entry.getKey().equals(referenceLanguage)
                        && !placeholders(entry.getValue()).equals(expected)) {
                    mismatched.put(entry.getKey(), entry.getValue());
                }
            }
            if (!mismatched.isEmpty()) {
                issues.add(new ValidationIssue(ValidationIssue.PLACEHOLDER_MISMATCH,
                        ValidationIssueSeverity.ERROR, key,
                        "占位符与 " + referenceLanguage + " 不一致",
                        new ArrayList<String>(mismatched.keySet()),
                        join(expected), mismatched));
            }
        }

        return new ValidationResult(issues, keysChecked);
    }

    private Map<String, String> keyTypes(GeneratedKeys keys) {
        Map<String, String> types = new LinkedHashMap<String, String>();
        for (String key : keys.getCodeKeys().keySet()) {
            types.put(key, "code");
        }
        for (String key : keys.getEnumKeys().keySet()) {
            if (!types.containsKey(key)) {
                types.put(key, "enum");
            }
        }
        return types;
    }

    private boolean matchesFormat(String type, String key) {
        if ("code".equals(type)) {
            return CODE_KEY.matcher(key).matches();
        }
        return ENUM_KEY.matcher(key).matches();
    }

    private Map<String, String> valuesByLanguage(String key,
                                                 Map<String, LocalMessages> localByLanguage,
                                                 List<String> languages) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String language : languages) {
            LocalMessages local = localByLanguage.get(language);
            String value = local == null ? null : local.get(key);
            if (!isBlank(value)) {
                values.put(language, value);
            }
        }
        return values;
    }

    private String referenceLanguage(Map<String, String> values) {
        for (String language : values.keySet()) {
            if ("zh_CN".equals(language)) {
                return language;
            }
        }
        return values.keySet().iterator().next();
    }

    private List<String> placeholders(String value) {
        List<String> result = new ArrayList<String>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        Collections.sort(result);
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String join(List<String> values) {
        if (values.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
