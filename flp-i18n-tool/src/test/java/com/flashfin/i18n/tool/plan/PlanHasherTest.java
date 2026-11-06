package com.flashfin.i18n.tool.plan;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PlanHasherTest {

    @Test
    public void hashIgnoresMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("command", "upload");
        first.put("count", 1);
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("count", 1);
        second.put("command", "upload");

        assertEquals(PlanHasher.hash(first), PlanHasher.hash(second));
        assertEquals(64, PlanHasher.hash(first).length());
    }

    @Test
    public void hashChangesWithContent() {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("value", "a");
        String before = PlanHasher.hash(plan);
        plan.put("value", "b");

        assertNotEquals(before, PlanHasher.hash(plan));
    }
}
