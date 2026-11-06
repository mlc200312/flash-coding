package com.flashfin.i18n.tool.diff;

import com.flashfin.i18n.tool.dictionary.LanguageCodes;
import com.flashfin.i18n.tool.dictionary.RemoteDictionary;
import com.flashfin.i18n.tool.messages.LocalMessages;
import com.flashfin.i18n.tool.scan.GeneratedKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiffEngine {

    private static final Comparator<DiffEntry> BY_KEY = new Comparator<DiffEntry>() {
        @Override
        public int compare(DiffEntry left, DiffEntry right) {
            return left.getKey().compareTo(right.getKey());
        }
    };

    public DiffResult diff(GeneratedKeys keys, Map<String, LocalMessages> localByLanguage,
                           Map<String, RemoteDictionary> remoteByLanguage) {
        Map<String, String> localTypes = localTypes(keys);
        Map<String, LanguageDiff> languages = new LinkedHashMap<String, LanguageDiff>();
        List<RemoteDictionary> unavailable = new ArrayList<RemoteDictionary>();
        int missingOnPlatformTotal = 0;
        int missingLocallyTotal = 0;
        int zhConflictsTotal = 0;

        for (Map.Entry<String, RemoteDictionary> entry : remoteByLanguage.entrySet()) {
            String language = entry.getKey();
            RemoteDictionary remote = entry.getValue();
            if (!remote.isAvailable()) {
                unavailable.add(remote);
                languages.put(language, new LanguageDiff(language, false,
                        Collections.<DiffEntry>emptyList(),
                        Collections.<DiffEntry>emptyList(),
                        Collections.<DiffEntry>emptyList()));
                continue;
            }

            LocalMessages local = localByLanguage.get(language);
            List<DiffEntry> missingOnPlatform = new ArrayList<DiffEntry>();
            for (Map.Entry<String, String> localType : localTypes.entrySet()) {
                String key = localType.getKey();
                String remoteValue = remote.getValues().get(key);
                if (isBlank(remoteValue)) {
                    missingOnPlatform.add(new DiffEntry(key, localType.getValue(),
                            local == null ? null : local.get(key), remoteValue));
                }
            }

            List<DiffEntry> missingLocally = new ArrayList<DiffEntry>();
            for (Map.Entry<String, String> remoteValue : remote.getValues().entrySet()) {
                if (isBlank(remoteValue.getValue()) || localTypes.containsKey(remoteValue.getKey())) {
                    continue;
                }
                missingLocally.add(new DiffEntry(remoteValue.getKey(),
                        typeOf(remoteValue.getKey()), null, remoteValue.getValue()));
            }

            List<DiffEntry> zhConflicts = new ArrayList<DiffEntry>();
            if (LanguageCodes.isChinese(language) && local != null) {
                for (Map.Entry<String, String> localType : localTypes.entrySet()) {
                    String key = localType.getKey();
                    String localValue = local.get(key);
                    String remoteValue = remote.getValues().get(key);
                    if (!isBlank(localValue) && !isBlank(remoteValue)
                            && !localValue.equals(remoteValue)) {
                        zhConflicts.add(new DiffEntry(key, localType.getValue(),
                                localValue, remoteValue));
                    }
                }
            }

            Collections.sort(missingOnPlatform, BY_KEY);
            Collections.sort(missingLocally, BY_KEY);
            Collections.sort(zhConflicts, BY_KEY);
            languages.put(language, new LanguageDiff(language, true,
                    missingOnPlatform, missingLocally, zhConflicts));
            missingOnPlatformTotal += missingOnPlatform.size();
            missingLocallyTotal += missingLocally.size();
            zhConflictsTotal += zhConflicts.size();
        }

        return new DiffResult(languages, unavailable, missingOnPlatformTotal,
                missingLocallyTotal, zhConflictsTotal);
    }

    private Map<String, String> localTypes(GeneratedKeys keys) {
        Map<String, String> types = new LinkedHashMap<String, String>();
        for (String key : keys.getCodeKeys().keySet()) {
            types.put(key, "code");
        }
        for (String key : keys.getEnumKeys().keySet()) {
            types.put(key, "enum");
        }
        return types;
    }

    private String typeOf(String key) {
        return key != null && key.startsWith("code_") ? "code" : "enum";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
