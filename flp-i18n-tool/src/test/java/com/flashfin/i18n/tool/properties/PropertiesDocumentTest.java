package com.flashfin.i18n.tool.properties;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PropertiesDocumentTest {

    @Test
    public void unchangedDocumentRendersOriginalBytes() {
        String content = "# header\n\nkey=value\n# trailing comment\n";
        assertEquals(content, PropertiesDocument.parse(content).render());
    }

    @Test
    public void escapesNonAsciiWhenValueIsWritten() {
        PropertiesDocument document = PropertiesDocument.parse("# header\ncode_100=old\n");
        document.setValue("code_100", "系统错误");

        String rendered = document.render();
        assertTrue(rendered.contains("code_100=\\u7CFB\\u7EDF\\u9519\\u8BEF"));
        assertTrue(rendered.startsWith("# header\n"));
    }

    @Test
    public void uncommentsPlaceholderAndKeepsOrder() {
        PropertiesDocument document = PropertiesDocument.parse(
                "# header\n#code_100=\nlast=value\n");
        document.setValue("code_100", "done");

        assertEquals("# header\ncode_100=done\nlast=value\n", document.render());
    }

    @Test
    public void appendsNewKeysAfterExistingContent() {
        PropertiesDocument document = PropertiesDocument.parse("# header\nfirst=1\n");
        document.addCommented("Demo_1");
        document.setValue("second", "2");

        assertEquals("# header\nfirst=1\n#Demo_1=\nsecond=2\n", document.render());
    }

    @Test
    public void detectsRawNonAsciiValueForNormalization() {
        PropertiesDocument document = PropertiesDocument.parse("key=系统\n");
        assertTrue(document.needsValueUpdate("key", "系统"));
        assertTrue(document.needsValueUpdate("missing", "系统"));
        assertFalse(PropertiesDocument.parse("key=\\u7CFB\\u7EDF\n")
                .needsValueUpdate("key", "系统"));
    }

    @Test
    public void parsesSeparatorsAndEscapes() {
        PropertiesDocument document = PropertiesDocument.parse(
                "alpha: one\nbeta two\n#gamma=\ndelta=line\\u0020one\n");
        assertEquals("one", document.getActiveValue("alpha"));
        assertEquals("two", document.getActiveValue("beta"));
        assertTrue(document.isCommented("gamma"));
        assertEquals("line one", document.getActiveValue("delta"));
        assertTrue(document.hasKey("gamma"));
    }

    @Test
    public void duplicateKeysUseLastValue() {
        PropertiesDocument document = PropertiesDocument.parse("key=first\nkey=second\n");
        assertEquals("second", document.getActiveValue("key"));
        assertEquals(1, document.getEntryKeys().size());
    }

    @Test
    public void preservesCrLfLineSeparator() {
        PropertiesDocument document = PropertiesDocument.parse("# header\r\nkey=value\r\n");
        document.setValue("key", "next");
        assertEquals("# header\r\nkey=next\r\n", document.render());
    }
}
