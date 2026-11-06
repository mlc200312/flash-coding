package com.flashfin.i18n.tool.platform;

import com.flashfin.i18n.tool.sync.UploadEntry;

public interface PlatformClient {

    PlatformSaveResult save(UploadEntry entry);
}
