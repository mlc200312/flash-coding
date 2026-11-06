package com.flashfin.i18n.tool.sync;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class UploadPlanTest {

    @Test
    public void planIdIsIndependentOfInputOrder() {
        UploadEntry first = new UploadEntry("demo", "flp-code", "code_1", "code", "en", "One");
        UploadEntry second = new UploadEntry("demo", "flp-enum", "Status_1", "enum", "en",
                "Active");

        UploadPlan left = UploadPlan.create("https://example.com/save",
                Arrays.asList("en"), Arrays.asList(first, second));
        UploadPlan right = UploadPlan.create("https://example.com/save",
                Arrays.asList("en"), Arrays.asList(second, first));

        assertEquals(left.getPlanId(), right.getPlanId());
        assertEquals("code_1", left.getEntries().get(0).getKey());
        assertEquals("Status_1", left.getEntries().get(1).getKey());
    }

    @Test
    public void planIdChangesWithValue() {
        List<UploadEntry> entries = new ArrayList<UploadEntry>();
        entries.add(new UploadEntry("demo", "flp-code", "code_1", "code", "en", "One"));
        UploadPlan first = UploadPlan.create("https://example.com/save",
                Arrays.asList("en"), entries);

        entries.set(0, new UploadEntry("demo", "flp-code", "code_1", "code", "en", "Two"));
        UploadPlan second = UploadPlan.create("https://example.com/save",
                Arrays.asList("en"), entries);

        assertNotEquals(first.getPlanId(), second.getPlanId());
    }

    @Test
    public void planIdChangesWithEndpoint() {
        List<UploadEntry> entries = Arrays.asList(
                new UploadEntry("demo", "flp-code", "code_1", "code", "en", "One"));
        UploadPlan first = UploadPlan.create("https://example.com/save",
                Arrays.asList("en"), entries);
        UploadPlan second = UploadPlan.create("https://other.example.com/save",
                Arrays.asList("en"), entries);

        assertNotEquals(first.getPlanId(), second.getPlanId());
    }
}
