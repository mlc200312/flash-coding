package com.flashfin.i18n.tool.platform;

public interface ReachabilityProbe {

    ProbeResult probe(String url, int timeoutMs);
}
