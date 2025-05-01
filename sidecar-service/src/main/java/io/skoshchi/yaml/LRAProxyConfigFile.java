package io.skoshchi.yaml;

public class LRAProxyConfigFile {
    private Proxy proxy;

    public LRAProxyConfigFile() {
    }

    public Proxy getProxy() {
        return proxy;
    }

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String toString() {
        return "LRAProxyConfigFile{" +
                "proxy=" + proxy +
                '}';
    }
}
