package com.flashfin.i18n.tool.sync;

import com.flashfin.i18n.tool.plan.PlanHasher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UploadPlan {

    private final List<UploadEntry> entries;
    private final Map<String, Object> canonical;
    private final String planId;

    private UploadPlan(List<UploadEntry> entries, Map<String, Object> canonical) {
        this.entries = Collections.unmodifiableList(new ArrayList<UploadEntry>(entries));
        this.canonical = canonical;
        this.planId = PlanHasher.hash(canonical);
    }

    public static UploadPlan create(String saveUrl, List<String> languages,
                                    List<UploadEntry> input) {
        List<UploadEntry> entries = new ArrayList<UploadEntry>(input);
        Collections.sort(entries, new Comparator<UploadEntry>() {
            @Override
            public int compare(UploadEntry left, UploadEntry right) {
                int result = left.getModule().compareTo(right.getModule());
                if (result != 0) {
                    return result;
                }
                result = left.getOs().compareTo(right.getOs());
                if (result != 0) {
                    return result;
                }
                result = left.getKey().compareTo(right.getKey());
                if (result != 0) {
                    return result;
                }
                return left.getLanguage().compareTo(right.getLanguage());
            }
        });

        Map<String, List<Map<String, Object>>> modules =
                new LinkedHashMap<String, List<Map<String, Object>>>();
        for (UploadEntry entry : entries) {
            List<Map<String, Object>> items = modules.get(entry.getModule());
            if (items == null) {
                items = new ArrayList<Map<String, Object>>();
                modules.put(entry.getModule(), items);
            }
            items.add(entry.toMap());
        }

        List<Map<String, Object>> moduleList = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<Map<String, Object>>> module : modules.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("module", module.getKey());
            item.put("entries", module.getValue());
            moduleList.add(item);
        }

        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put("command", "upload");
        canonical.put("saveUrl", saveUrl);
        canonical.put("languages", languages);
        canonical.put("modules", moduleList);
        return new UploadPlan(entries, canonical);
    }

    public String getPlanId() {
        return planId;
    }

    public List<UploadEntry> getEntries() {
        return entries;
    }

    public Map<String, Object> toCanonicalMap() {
        return canonical;
    }

    public Map<String, Object> toOutput() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("planId", planId);
        data.put("entryCount", entries.size());
        data.put("moduleCount", canonical.get("modules") instanceof List
                ? ((List<?>) canonical.get("modules")).size() : 0);
        data.put("entries", outputEntries(entries));
        return data;
    }

    private static List<Map<String, Object>> outputEntries(List<UploadEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (UploadEntry entry : entries) {
            result.add(entry.toOutput());
        }
        return result;
    }
}
