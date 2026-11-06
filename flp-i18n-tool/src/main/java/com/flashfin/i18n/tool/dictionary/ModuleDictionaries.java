package com.flashfin.i18n.tool.dictionary;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleDictionaries {

    private ModuleDictionaries() {
    }

    public static String enumOs(String codeOs) {
        if ("flp-code".equals(codeOs)) {
            return "flp-enum";
        }
        if ("common-code".equals(codeOs)) {
            return "common-enum";
        }
        return codeOs;
    }

    public static RemoteDictionary merge(String language, String codeOs,
                                         RemoteDictionary code, String enumOs,
                                         RemoteDictionary enums) {
        if (!code.isAvailable()) {
            return RemoteDictionary.unavailable(language, code.getUrl(), code.getErrorCode(),
                    "os=" + codeOs + ": " + code.getErrorMessage());
        }
        if (!enums.isAvailable()) {
            return RemoteDictionary.unavailable(language, enums.getUrl(), enums.getErrorCode(),
                    "os=" + enumOs + ": " + enums.getErrorMessage());
        }
        Map<String, String> merged = new LinkedHashMap<String, String>(code.getValues());
        merged.putAll(enums.getValues());
        return RemoteDictionary.available(language, code.getUrl(), merged);
    }
}
