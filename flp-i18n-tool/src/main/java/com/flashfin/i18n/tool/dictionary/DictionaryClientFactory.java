package com.flashfin.i18n.tool.dictionary;

import com.flashfin.i18n.tool.config.PlatformConfig;

public interface DictionaryClientFactory {

    DictionaryClient create(PlatformConfig platform);

    static DictionaryClientFactory http() {
        return new DictionaryClientFactory() {
            @Override
            public DictionaryClient create(PlatformConfig platform) {
                return new HttpDictionaryClient(platform.getDictionaryBase(),
                        platform.getTimeoutMs(), platform.getMaxRetries());
            }
        };
    }
}
