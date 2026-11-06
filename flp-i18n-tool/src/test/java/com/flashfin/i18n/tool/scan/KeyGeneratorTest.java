package com.flashfin.i18n.tool.scan;

import com.flashfin.i18n.tool.fixtures.DuplicateEnums;
import com.flashfin.i18n.tool.fixtures.MixedEnum;
import com.flashfin.i18n.tool.fixtures.NotAnEnum;
import com.flashfin.i18n.tool.fixtures.SampleErrorEnum;
import com.flashfin.i18n.tool.fixtures.SampleStatusEnum;
import com.flashfin.i18n.tool.fixtures.StringModeEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KeyGeneratorTest {

    private final KeyGenerator generator = new KeyGenerator(KeyRules.of(
            "com.flashfin.i18n.tool.fixtures.FakeEnumerable",
            "com.flashfin.i18n.tool.fixtures.FakeResultCode",
            "com.flashfin.i18n.tool.fixtures.FakeStringEnumerable"));

    @Test
    public void generatesEnumerableKeysFromNumericCode() throws Exception {
        GeneratedKeys keys = generator.generate(
                Collections.<Class<?>>singletonList(SampleStatusEnum.class),
                getClass().getClassLoader());

        assertEquals(2, keys.getEnumKeys().size());
        assertEquals("SampleStatusEnum_1",
                keys.getEnumKeys().keySet().iterator().next());
        assertTrue(keys.getCodeKeys().isEmpty());
    }

    @Test
    public void generatesCodeKeysForResultCode() throws Exception {
        GeneratedKeys keys = generator.generate(
                Collections.<Class<?>>singletonList(SampleErrorEnum.class),
                getClass().getClassLoader());

        assertEquals(2, keys.getCodeKeys().size());
        assertTrue(keys.getCodeKeys().containsKey("code_0"));
        assertTrue(keys.getCodeKeys().containsKey("code_100"));
        assertTrue(keys.getEnumKeys().isEmpty());
    }

    @Test
    public void stringEnumerableChangesCodeSource() throws Exception {
        GeneratedKeys keys = generator.generate(
                Collections.<Class<?>>singletonList(StringModeEnum.class),
                getClass().getClassLoader());

        assertEquals(2, keys.getEnumKeys().size());
        assertTrue(keys.getEnumKeys().containsKey("StringModeEnum_F"));
        assertTrue(keys.getEnumKeys().containsKey("StringModeEnum_S"));
        assertFalse(keys.getEnumKeys().containsKey("StringModeEnum_10"));
    }

    @Test
    public void classImplementingBothInterfacesProducesBothKeyTypes() throws Exception {
        GeneratedKeys keys = generator.generate(
                Collections.<Class<?>>singletonList(MixedEnum.class),
                getClass().getClassLoader());

        assertTrue(keys.getCodeKeys().containsKey("code_9"));
        assertTrue(keys.getEnumKeys().containsKey("MixedEnum_9"));
    }

    @Test
    public void ignoresNonEnumClasses() throws Exception {
        GeneratedKeys keys = generator.generate(
                Collections.<Class<?>>singletonList(NotAnEnum.class),
                getClass().getClassLoader());

        assertTrue(keys.getCodeKeys().isEmpty());
        assertTrue(keys.getEnumKeys().isEmpty());
    }

    @Test
    public void reportsDuplicateKeys() throws Exception {
        GeneratedKeys keys = generator.generate(Arrays.<Class<?>>asList(
                DuplicateEnums.DupOne.class, DuplicateEnums.DupTwo.class),
                getClass().getClassLoader());

        assertEquals(1, keys.getCodeKeys().size());
        assertEquals(1, keys.getDuplicates().size());
        GeneratedKeys.DuplicateKey duplicate =
                keys.getDuplicates().values().iterator().next();
        assertEquals("code_7", duplicate.getKey());
        assertEquals(DuplicateEnums.DupOne.class.getName(), duplicate.getFirstSource());
        assertEquals(DuplicateEnums.DupTwo.class.getName(), duplicate.getDuplicateSource());
    }

    @Test
    public void failsWhenFrameworkTypesMissing() throws Exception {
        KeyGenerator missing = new KeyGenerator(KeyRules.of(
                "com.example.MissingEnumerable", "com.example.MissingResultCode", null));
        try {
            missing.generate(Collections.<Class<?>>singletonList(SampleStatusEnum.class),
                    getClass().getClassLoader());
            fail("expected ScanException");
        } catch (ScanException e) {
            assertEquals(ScanException.FRAMEWORK_CLASS_MISSING, e.getCode());
        }
    }
}
