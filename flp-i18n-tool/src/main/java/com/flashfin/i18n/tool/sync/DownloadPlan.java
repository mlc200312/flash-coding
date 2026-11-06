package com.flashfin.i18n.tool.sync;

import com.flashfin.i18n.tool.plan.PlanHasher;
import com.flashfin.i18n.tool.properties.PropertiesDocument;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DownloadPlan {

    public static final String ADD = "add";
    public static final String COMMENT = "comment";
    public static final String UPDATE = "update";

    private final List<FilePlan> files;
    private final Map<String, Object> canonical;
    private final String planId;

    private DownloadPlan(List<FilePlan> files, Map<String, Object> canonical) {
        this.files = Collections.unmodifiableList(new ArrayList<FilePlan>(files));
        this.canonical = canonical;
        this.planId = PlanHasher.hash(canonical);
    }

    public static DownloadPlan create(String dictionaryBase, List<String> languages,
                                      List<FilePlan> input) {
        List<FilePlan> files = new ArrayList<FilePlan>(input);
        Collections.sort(files, new Comparator<FilePlan>() {
            @Override
            public int compare(FilePlan left, FilePlan right) {
                int result = left.getModule().compareTo(right.getModule());
                if (result != 0) {
                    return result;
                }
                return left.getRelativePath().compareTo(right.getRelativePath());
            }
        });
        for (FilePlan file : files) {
            Collections.sort(file.getOperations(), new Comparator<Operation>() {
                @Override
                public int compare(Operation left, Operation right) {
                    int result = left.getKey().compareTo(right.getKey());
                    if (result != 0) {
                        return result;
                    }
                    return left.getAction().compareTo(right.getAction());
                }
            });
        }

        Map<String, List<Map<String, Object>>> modules =
                new LinkedHashMap<String, List<Map<String, Object>>>();
        for (FilePlan file : files) {
            List<Map<String, Object>> moduleFiles = modules.get(file.getModule());
            if (moduleFiles == null) {
                moduleFiles = new ArrayList<Map<String, Object>>();
                modules.put(file.getModule(), moduleFiles);
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("file", file.getRelativePath());
            List<Map<String, Object>> operations = new ArrayList<Map<String, Object>>();
            for (Operation operation : file.getOperations()) {
                operations.add(operation.toMap());
            }
            item.put("operations", operations);
            moduleFiles.add(item);
        }

        List<Map<String, Object>> moduleList = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<Map<String, Object>>> module : modules.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("module", module.getKey());
            item.put("files", module.getValue());
            moduleList.add(item);
        }

        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put("command", "download");
        canonical.put("dictionaryBase", dictionaryBase);
        canonical.put("languages", languages);
        canonical.put("modules", moduleList);
        return new DownloadPlan(files, canonical);
    }

    public String getPlanId() {
        return planId;
    }

    public List<FilePlan> getFiles() {
        return files;
    }

    public Map<String, Object> toOutput() {
        int operations = 0;
        List<Map<String, Object>> outputFiles = new ArrayList<Map<String, Object>>();
        for (FilePlan file : files) {
            operations += file.getOperations().size();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("module", file.getModule());
            item.put("file", file.getRelativePath());
            List<Map<String, Object>> ops = new ArrayList<Map<String, Object>>();
            for (Operation operation : file.getOperations()) {
                ops.add(operation.toOutput());
            }
            item.put("operations", ops);
            outputFiles.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("planId", planId);
        data.put("fileCount", files.size());
        data.put("operationCount", operations);
        data.put("files", outputFiles);
        return data;
    }

    public static final class FilePlan {

        private final String module;
        private final Path file;
        private final String relativePath;
        private final PropertiesDocument document;
        private final List<Operation> operations;

        public FilePlan(String module, Path file, String relativePath,
                        PropertiesDocument document, List<Operation> operations) {
            this.module = module;
            this.file = file;
            this.relativePath = relativePath;
            this.document = document;
            this.operations = new ArrayList<Operation>(operations);
        }

        public String getModule() {
            return module;
        }

        public Path getFile() {
            return file;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public PropertiesDocument getDocument() {
            return document;
        }

        public List<Operation> getOperations() {
            return operations;
        }
    }

    public static final class Operation {

        private final String action;
        private final String key;
        private final String value;

        public Operation(String action, String key, String value) {
            this.action = action;
            this.key = key;
            this.value = value;
        }

        public String getAction() {
            return action;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("action", action);
            map.put("key", key);
            map.put("value", value);
            return map;
        }

        public Map<String, Object> toOutput() {
            return toMap();
        }
    }
}
