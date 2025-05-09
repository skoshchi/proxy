package io.skoshchi.yaml;

public class LRAProxyConfig {
    private LRAProxySettings LRAProxySettings;

    public LRAProxyConfig() {
    }

    public LRAProxySettings getProxy() {
        return LRAProxySettings;
    }

    public void setProxy(LRAProxySettings LRAProxySettings) {
        this.LRAProxySettings = LRAProxySettings;
    }

    @Override
    public String toString() {
        return "LRAProxyConfigFile{" +
                "proxy=" + LRAProxySettings +
                '}';
    }
}
