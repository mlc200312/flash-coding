package com.flashfin.i18n.tool.diff;

import com.flashfin.i18n.tool.dictionary.RemoteDictionary;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiffEngineTest {

    private static final String ZH = "zh_CN";
    private static final String EN = "en";
    private static final String TH = "th";

    private final DiffEngine engine = new DiffEngine();
    private final KeyGenerator generator = new KeyGenerator(KeyRules.of(
            "com.flashfin.i18n.tool.fixtures.FakeEnumerable",
            "com.flashfin.i18n.tool.fixtures.FakeResultCode",
            "com.flashfin.i18n.tool.fixtures.FakeStringEnumerable"));

    @Test
    public void reportsNoDifferencesWhenAligned() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> values = allValues("成功", "系统错误", "启用", "禁用");

        DiffResult result = engine.diff(keys,
                local(ZH, values), remote(ZH, values));

        assertEquals(0, result.getMissingOnPlatformTotal());
        assertEquals(0, result.getMissingLocallyTotal());
        assertEquals(0, result.getZhConflictsTotal());
        assertTrue(result.getRemoteUnavailable().isEmpty());
        LanguageDiff zh = result.getLanguages().get(ZH);
        assertTrue(zh.isRemoteAvailable());
        assertTrue(zh.getMissingOnPlatform().isEmpty());
        assertTrue(zh.getMissingLocally().isEmpty());
        assertTrue(zh.getZhConflicts().isEmpty());
    }

    @Test
    public void reportsKeysMissingOnPlatform() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> localValues = allValues("成功", "系统错误", "启用", "禁用");
        Map<String, String> remoteValues = values(
                "code_0", "成功",
                "code_100", "系统错误",
                "SampleStatusEnum_1", "启用");

        DiffResult result = engine.diff(keys,
                local(ZH, localValues), remote(ZH, remoteValues));

        assertEquals(1, result.getMissingOnPlatformTotal());
        DiffEntry entry = result.getLanguages().get(ZH).getMissingOnPlatform().get(0);
        assertEquals("SampleStatusEnum_2", entry.getKey());
        assertEquals("enum", entry.getType());
        assertEquals("禁用", entry.getLocalValue());
        assertNull(entry.getRemoteValue());
    }

    @Test
    public void treatsBlankRemoteValueAsMissing() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> localValues = allValues("成功", "系统错误", "启用", "禁用");
        Map<String, String> remoteValues = allValues("   ", "系统错误", "启用", "禁用");

        DiffResult result = engine.diff(keys,
                local(ZH, localValues), remote(ZH, remoteValues));

        assertEquals(1, result.getMissingOnPlatformTotal());
        DiffEntry entry = result.getLanguages().get(ZH).getMissingOnPlatform().get(0);
        assertEquals("code_0", entry.getKey());
        assertEquals("   ", entry.getRemoteValue());
    }

    @Test
    public void reportsRemoteKeysMissingLocally() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> localValues = allValues("成功", "系统错误", "启用", "禁用");
        Map<String, String> remoteValues = allValues("成功", "系统错误", "启用", "禁用");
        remoteValues.put("RemoteOnly_1", "远端独有");
        remoteValues.put("code_999", "远端错误");

        DiffResult result = engine.diff(keys,
                local(ZH, localValues), remote(ZH, remoteValues));

        assertEquals(2, result.getMissingLocallyTotal());
        Map<String, DiffEntry> byKey = byKey(
                result.getLanguages().get(ZH).getMissingLocally());
        assertEquals("enum", byKey.get("RemoteOnly_1").getType());
        assertNull(byKey.get("RemoteOnly_1").getLocalValue());
        assertEquals("远端独有", byKey.get("RemoteOnly_1").getRemoteValue());
        assertEquals("code", byKey.get("code_999").getType());
    }

    @Test
    public void reportsChineseConflicts() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> localValues = allValues("下单失败", "系统错误", "启用", "禁用");
        Map<String, String> remoteValues = allValues("下单失败！", "系统错误", "启用", "禁用");

        DiffResult result = engine.diff(keys,
                local(ZH, localValues), remote(ZH, remoteValues));

        assertEquals(1, result.getZhConflictsTotal());
        DiffEntry conflict = result.getLanguages().get(ZH).getZhConflicts().get(0);
        assertEquals("code_0", conflict.getKey());
        assertEquals("下单失败", conflict.getLocalValue());
        assertEquals("下单失败！", conflict.getRemoteValue());
        assertEquals(0, result.getMissingOnPlatformTotal());
    }

    @Test
    public void ignoresEqualChineseValues() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> values = allValues("失败", "系统错误", "启用", "禁用");

        DiffResult result = engine.diff(keys,
                local(ZH, values), remote(ZH, values));

        assertEquals(0, result.getZhConflictsTotal());
        assertTrue(result.getLanguages().get(ZH).getZhConflicts().isEmpty());
    }

    @Test
    public void marksUnavailableRemoteDictionary() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, RemoteDictionary> remote = new LinkedHashMap<String, RemoteDictionary>();
        remote.put(ZH, RemoteDictionary.unavailable(ZH,
                "http://127.0.0.1/flp-code/lang/zh.json",
                "DICTIONARY_FETCH_FAILED", "connection refused"));

        DiffResult result = engine.diff(keys,
                local(ZH, allValues("失败", "错误", "启用", "禁用")), remote);

        assertEquals(1, result.getRemoteUnavailable().size());
        assertEquals("DICTIONARY_FETCH_FAILED",
                result.getRemoteUnavailable().get(0).getErrorCode());
        LanguageDiff zh = result.getLanguages().get(ZH);
        assertFalse(zh.isRemoteAvailable());
        assertTrue(zh.getMissingOnPlatform().isEmpty());
        assertTrue(zh.getMissingLocally().isEmpty());
        assertEquals(0, result.getMissingOnPlatformTotal());
    }

    @Test
    public void reportsDifferencesPerLanguageIndependently() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> enValues = allValues("ok", "error", "enabled", "disabled");
        Map<String, String> thValues = allValues("ok-th", "error-th", "enabled-th", "disabled-th");
        Map<String, String> remoteEn = values(
                "code_0", "ok",
                "SampleStatusEnum_1", "enabled",
                "SampleStatusEnum_2", "disabled");

        Map<String, LocalMessages> local = new LinkedHashMap<String, LocalMessages>();
        local.put(EN, messages(EN, enValues));
        local.put(TH, messages(TH, thValues));
        Map<String, RemoteDictionary> remote = new LinkedHashMap<String, RemoteDictionary>();
        remote.put(EN, RemoteDictionary.available(EN, "http://example.com/en", remoteEn));
        remote.put(TH, RemoteDictionary.available(TH, "http://example.com/th", thValues));

        DiffResult result = engine.diff(keys, local, remote);

        assertEquals(1, result.getMissingOnPlatformTotal());
        assertEquals("code_100",
                result.getLanguages().get(EN).getMissingOnPlatform().get(0).getKey());
        assertTrue(result.getLanguages().get(TH).getMissingOnPlatform().isEmpty());
    }

    @Test
    public void classifiesCodeAndEnumEntries() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> localValues = allValues("成功", "系统错误", "启用", "禁用");
        Map<String, String> remoteValues = values(
                "code_999", "远端错误",
                "Extra_1", "远端枚举");

        DiffResult result = engine.diff(keys,
                local(EN, localValues), remote(EN, remoteValues));

        Map<String, DiffEntry> missingOnPlatform = byKey(
                result.getLanguages().get(EN).getMissingOnPlatform());
        assertEquals("code", missingOnPlatform.get("code_0").getType());
        assertEquals("code", missingOnPlatform.get("code_100").getType());
        assertEquals("enum", missingOnPlatform.get("SampleStatusEnum_1").getType());
        assertEquals("enum", missingOnPlatform.get("SampleStatusEnum_2").getType());

        Map<String, DiffEntry> missingLocally = byKey(
                result.getLanguages().get(EN).getMissingLocally());
        assertEquals("code", missingLocally.get("code_999").getType());
        assertEquals("enum", missingLocally.get("Extra_1").getType());
    }

    @Test
    public void treatsBlankRemoteAcrossLanguagesAsMissingOnPlatform() throws Exception {
        GeneratedKeys keys = keys();
        Map<String, String> enValues = allValues("ok", "error", "enabled", "disabled");
        Map<String, String> thValues = allValues("ok-th", "error-th", "enabled-th", "disabled-th");
        Map<String, String> remoteEn = allValues("ok", "error", "", "disabled");
        Map<String, String> remoteTh = allValues("ok-th", "error-th", "  ", "disabled-th");
        remoteEn.put("Ghost_1", "");
        remoteTh.put("Ghost_1", "  ");

        Map<String, LocalMessages> local = new LinkedHashMap<String, LocalMessages>();
        local.put(EN, messages(EN, enValues));
        local.put(TH, messages(TH, thValues));
        Map<String, RemoteDictionary> remote = new LinkedHashMap<String, RemoteDictionary>();
        remote.put(EN, RemoteDictionary.available(EN, "http://example.com/en", remoteEn));
        remote.put(TH, RemoteDictionary.available(TH, "http://example.com/th", remoteTh));

        DiffResult result = engine.diff(keys, local, remote);

        assertEquals(2, result.getMissingOnPlatformTotal());
        assertEquals("SampleStatusEnum_1",
                result.getLanguages().get(EN).getMissingOnPlatform().get(0).getKey());
        assertEquals("SampleStatusEnum_1",
                result.getLanguages().get(TH).getMissingOnPlatform().get(0).getKey());
        assertTrue(result.getLanguages().get(EN).getMissingLocally().isEmpty());
        assertTrue(result.getLanguages().get(TH).getMissingLocally().isEmpty());
    }

    private GeneratedKeys keys() throws Exception {
        return generator.generate(Arrays.<Class<?>>asList(
                SampleErrorEnum.class, SampleStatusEnum.class), getClass().getClassLoader());
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

    private static Map<String, RemoteDictionary> remote(String language,
                                                        Map<String, String> values) {
        Map<String, RemoteDictionary> result = new LinkedHashMap<String, RemoteDictionary>();
        result.put(language, RemoteDictionary.available(language,
                "http://example.com/" + language, values));
        return result;
    }

    private static LocalMessages messages(String language, Map<String, String> values) {
        return new LocalMessages(language, values, Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    private static Map<String, DiffEntry> byKey(List<DiffEntry> entries) {
        Map<String, DiffEntry> result = new LinkedHashMap<String, DiffEntry>();
        for (DiffEntry entry : entries) {
            result.put(entry.getKey(), entry);
        }
        return result;
    }
}
