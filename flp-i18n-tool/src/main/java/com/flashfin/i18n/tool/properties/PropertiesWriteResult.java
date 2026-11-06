package com.flashfin.i18n.tool.properties;

import java.nio.file.Path;

public final class PropertiesWriteResult {

    private final boolean changed;
    private final Path backup;

    public PropertiesWriteResult(boolean changed, Path backup) {
        this.changed = changed;
        this.backup = backup;
    }

    public boolean isChanged() {
        return changed;
    }

    public Path getBackup() {
        return backup;
    }
}
