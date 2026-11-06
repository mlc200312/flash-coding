package com.flashfin.i18n.tool.dictionary;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LanguageCodesTest {

    @Test
    public void canonicalizesAliases() {
        assertEquals("zh_CN", LanguageCodes.canonical("zh_CN"));
        assertEquals("zh_CN", LanguageCodes.canonical("zh-CN"));
        assertEquals("zh_CN", LanguageCodes.canonical("zh"));
        assertEquals("en_id", LanguageCodes.canonical("en_ID"));
        assertEquals("en_id", LanguageCodes.canonical("en_id"));
        assertEquals("en", LanguageCodes.canonical("en"));
    }

    @Test
    public void mapsLanguagesToCdnPaths() throws Exception {
        assertEquals("zh", LanguageCodes.cdnPath("zh_CN"));
        assertEquals("en", LanguageCodes.cdnPath("en"));
        assertEquals("th", LanguageCodes.cdnPath("th"));
        assertEquals("en_id", LanguageCodes.cdnPath("id"));
        assertEquals("en_id", LanguageCodes.cdnPath("en_ID"));
    }

    @Test
    public void mapsLanguagesToPropertiesSuffixes() {
        assertEquals("zh_CN", LanguageCodes.propertiesSuffix("zh_CN"));
        assertEquals("en", LanguageCodes.propertiesSuffix("en"));
        assertEquals("th", LanguageCodes.propertiesSuffix("th"));
        assertEquals("en_ID", LanguageCodes.propertiesSuffix("en_id"));
    }

    @Test
    public void rejectsUnsafeLanguageCodes() {
        try {
            LanguageCodes.cdnPath("../etc");
            fail("expected DictionaryException");
        } catch (DictionaryException e) {
            assertEquals(DictionaryException.INVALID_LANGUAGE, e.getCode());
        }
    }

    @Test
    public void identifiesChineseLanguages() {
        assertTrue(LanguageCodes.isChinese("zh_CN"));
        assertTrue(LanguageCodes.isChinese("zh-CN"));
        assertFalse(LanguageCodes.isChinese("en"));
        assertFalse(LanguageCodes.isChinese(null));
    }
}
