package com.flashfin.i18n.tool.scan;

import java.nio.file.Path;
import java.util.List;

public interface ModuleClasspathBuilder {

    ClasspathResult build(ResolvedModule module) throws ScanException;
}
