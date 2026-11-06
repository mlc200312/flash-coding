package com.flashfin.i18n.tool.platform;

import com.flashfin.i18n.tool.config.PlatformConfig;

public interface PlatformClientFactory {

    PlatformClient create(PlatformConfig platform, String token);

    static PlatformClientFactory http() {
        return new PlatformClientFactory() {
            @Override
            public PlatformClient create(PlatformConfig platform, String token) {
                return new HttpPlatformClient(platform.getSaveUrl(), token,
                        platform.getTimeoutMs(), platform.getMaxRetries());
            }
        };
    }
}
