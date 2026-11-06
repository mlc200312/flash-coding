package com.flashfin.i18n.tool.dictionary;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModuleDictionariesTest {

    @Test
    public void derivesEnumOsFromCodeOs() {
        assertEquals("flp-enum", ModuleDictionaries.enumOs("flp-code"));
        assertEquals("common-enum", ModuleDictionaries.enumOs("common-code"));
        assertEquals("flp-enum", ModuleDictionaries.enumOs("flp-enum"));
        assertEquals("common-enum", ModuleDictionaries.enumOs("common-enum"));
    }

    @Test
    public void mergesCodeAndEnumDictionaries() {
        Map<String, String> code = new LinkedHashMap<String, String>();
        code.put("code_0", "成功");
        Map<String, String> enums = new LinkedHashMap<String, String>();
        enums.put("ActionType_0", "充值");

        RemoteDictionary merged = ModuleDictionaries.merge("zh_CN", "flp-code",
                RemoteDictionary.available("zh_CN", "code-url", code),
                "flp-enum", RemoteDictionary.available("zh_CN", "enum-url", enums));

        assertTrue(merged.isAvailable());
        assertEquals("成功", merged.getValues().get("code_0"));
        assertEquals("充值", merged.getValues().get("ActionType_0"));
        assertEquals(2, merged.getValues().size());
    }

    @Test
    public void failsWhenCodeDictionaryUnavailable() {
        RemoteDictionary merged = ModuleDictionaries.merge("zh_CN", "flp-code",
                RemoteDictionary.unavailable("zh_CN", "code-url", "DICTIONARY_FETCH_FAILED", "boom"),
                "flp-enum", RemoteDictionary.available("zh_CN", "enum-url",
                        new LinkedHashMap<String, String>()));

        assertFalse(merged.isAvailable());
        assertEquals("DICTIONARY_FETCH_FAILED", merged.getErrorCode());
        assertEquals("code-url", merged.getUrl());
        assertTrue(merged.getErrorMessage().contains("os=flp-code"));
    }

    @Test
    public void failsWhenEnumDictionaryUnavailable() {
        Map<String, String> code = new LinkedHashMap<String, String>();
        code.put("code_0", "成功");

        RemoteDictionary merged = ModuleDictionaries.merge("zh_CN", "flp-code",
                RemoteDictionary.available("zh_CN", "code-url", code),
                "flp-enum", RemoteDictionary.unavailable("zh_CN", "enum-url",
                        DictionaryException.NOT_FOUND, "missing"));

        assertFalse(merged.isAvailable());
        assertEquals(DictionaryException.NOT_FOUND, merged.getErrorCode());
        assertEquals("enum-url", merged.getUrl());
        assertTrue(merged.getErrorMessage().contains("os=flp-enum"));
    }
}
