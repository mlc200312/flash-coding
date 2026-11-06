package com.flashfin.i18n.tool.validate;

import com.flashfin.i18n.tool.fixtures.DuplicateEnums;
import com.flashfin.i18n.tool.fixtures.InvalidKeyEnum;
import com.flashfin.i18n.tool.fixtures.SampleErrorEnum;
import com.flashfin.i18n.tool.fixtures.SampleStatusEnum;
import com.flashfin.i18n.tool.messages.LocalMessages;
import com.flashfin.i18n.tool.scan.GeneratedKeys;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ValidatorTest {

    private static final String ZH = "zh_CN";
    private static final String EN = "en";
    private static final String TH = "th";

    private final Validator validator = new Validator();
    private final KeyGenerator generator = new KeyGenerator(KeyRules.of(
            "com.flashfin.i18n.tool.fixtures.FakeEnumerable",
            "com.flashfin.i18n.tool.fixtures.FakeResultCode",
            "com.flashfin.i18n.tool.fixtures.FakeStringEnumerable"));

    @Test
    public void acceptsValidKeysWithAlignedTranslations() throws Exception {
        GeneratedKeys keys = keys();
        ValidationResult result = validator.validate(keys,
                local(ZH, allValues("成功", "系统错误", "启用", "禁用")),
                Collections.singletonList(ZH));

        assertTrue(result.isValid());
        assertEquals(0, result.getIssues().size());
        assertEquals(4, result.getKeysChecked());
    }

    @Test
    public void flagsInvalidKeyFormat() throws Exception {
        GeneratedKeys keys = generator.generate(
                Collections.<Class<?>>singletonList(InvalidKeyEnum.class),
                getClass().getClassLoader());
        Map<String, String> values = values("InvalidKeyEnum_bad key", "坏的 key");

        ValidationResult result = validator.validate(keys,
                local(ZH, values), Collections.singletonList(ZH));

        ValidationIssue issue = onlyIssue(result, ValidationIssue.INVALID_KEY_FORMAT);
        assertEquals(ValidationIssueSeverity.ERROR, issue.getSeverity());
        assertEquals("InvalidKeyEnum_bad key", issue.getKey());
        assertFalse(result.isValid());
    }

    @Test
    public void flagsDuplicateKeys() throws Exception {
        GeneratedKeys keys = generator.generate(Arrays.<Class<?>>asList(
                DuplicateEnums.DupOne.class, DuplicateEnums.DupTwo.class),
                getClass().getClassLoader());

        ValidationResult result = validator.validate(keys,
                local(ZH, values("code_7", "重复")), Collections.singletonList(ZH));

        ValidationIssue issue = onlyIssue(result, ValidationIssue.DUPLICATE_KEY);
        assertEquals(ValidationIssueSeverity.ERROR, issue.getSeverity());
        assertEquals("code_7", issue.getKey());
        assertFalse(result.isValid());
    }

    @Test
    public void warnsWhenTranslationsAreMissing() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, LocalMessages> local = new LinkedHashMap<String, LocalMessages>();
        local.put(ZH, messages(ZH, allValues("成功", "系统错误", "启用", "禁用")));

        ValidationResult result = validator.validate(keys, local, Arrays.asList(ZH, EN));

        assertEquals(4, countIssues(result, ValidationIssue.EMPTY_VALUE));
        ValidationIssue issue = issueFor(result, ValidationIssue.EMPTY_VALUE, "code_100");
        assertNotNull(issue);
        assertEquals(ValidationIssueSeverity.WARNING, issue.getSeverity());
        assertEquals(Collections.singletonList(EN), issue.getLanguages());
        assertTrue(result.isValid());
    }

    @Test
    public void warnsForAllMissingLanguages() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, LocalMessages> local = new LinkedHashMap<String, LocalMessages>();
        local.put(ZH, messages(ZH, allValues("成功", "系统错误", "启用", "禁用")));

        ValidationResult result = validator.validate(keys, local, Arrays.asList(ZH, EN, TH));

        ValidationIssue issue = issueFor(result, ValidationIssue.EMPTY_VALUE, "code_0");
        assertNotNull(issue);
        assertEquals(Arrays.asList(EN, TH), issue.getLanguages());
        assertEquals(4, countIssues(result, ValidationIssue.EMPTY_VALUE));
    }

    @Test
    public void flagsPlaceholderMismatchAgainstChinese() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> zhValues = allValues("下单失败{0}", "系统错误", "启用", "禁用");
        Map<String, String> enValues = allValues("Order failed", "System error",
                "Enabled", "Disabled");
        Map<String, LocalMessages> local = new LinkedHashMap<String, LocalMessages>();
        local.put(ZH, messages(ZH, zhValues));
        local.put(EN, messages(EN, enValues));

        ValidationResult result = validator.validate(keys, local, Arrays.asList(ZH, EN));

        ValidationIssue issue = onlyIssue(result, ValidationIssue.PLACEHOLDER_MISMATCH);
        assertEquals(ValidationIssueSeverity.ERROR, issue.getSeverity());
        assertEquals("code_0", issue.getKey());
        assertEquals("{0}", issue.getExpected());
        assertEquals(Collections.singletonList(EN), issue.getLanguages());
        assertEquals("Order failed", issue.getActual().get(EN));
        assertFalse(result.isValid());
    }

    @Test
    public void acceptsSamePlaceholdersInDifferentOrder() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> zhValues = allValues("{0} 与 {1}", "系统错误", "启用", "禁用");
        Map<String, String> enValues = allValues("{1} and {0}", "System error",
                "Enabled", "Disabled");
        Map<String, LocalMessages> local = new LinkedHashMap<String, LocalMessages>();
        local.put(ZH, messages(ZH, zhValues));
        local.put(EN, messages(EN, enValues));

        ValidationResult result = validator.validate(keys, local, Arrays.asList(ZH, EN));

        assertEquals(0, countIssues(result, ValidationIssue.PLACEHOLDER_MISMATCH));
        assertTrue(result.isValid());
    }

    private GeneratedKeys keys() throws Exception {
        return generator.generate(Arrays.<Class<?>>asList(
                SampleErrorEnum.class, SampleStatusEnum.class), getClass().getClassLoader());
    }

    private ValidationIssue onlyIssue(ValidationResult result, String type) {
        List<ValidationIssue> issues = result.getIssues();
        assertEquals(1, issues.size());
        assertEquals(type, issues.get(0).getType());
        return issues.get(0);
    }

    private int countIssues(ValidationResult result, String type) {
        int count = 0;
        for (ValidationIssue issue : result.getIssues()) {
            if (type.equals(issue.getType())) {
                count++;
            }
        }
        return count;
    }

    private ValidationIssue issueFor(ValidationResult result, String type, String key) {
        for (ValidationIssue issue : result.getIssues()) {
            if (type.equals(issue.getType()) && key.equals(issue.getKey())) {
                return issue;
            }
        }
        return null;
    }

    private static Map<String, String> allValues(String code0, String code100,
                                                 String status1, String status2) {
        return values(
                "code_0", code0,
                "code_100", code100,
                "SampleStatusEnum_1", status1,
                "SampleStatusEnum_2", status2);
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static Map<String, LocalMessages> local(String language, Map<String, String> values) {
        Map<String, LocalMessages> result = new LinkedHashMap<String, LocalMessages>();
        result.put(language, messages(language, values));
        return result;
    }

    private static LocalMessages messages(String language, Map<String, String> values) {
        return new LocalMessages(language, values, Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }
}
