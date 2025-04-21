package io.skoshchi.yaml;

public class LRAProxyConfigFile {
    private LRAProxy lraProxy;

    public LRAProxyConfigFile() {
    }

    public LRAProxy getLraProxy() {
        return lraProxy;
    }

    public void setLraProxy(LRAProxy lraProxy) {
        this.lraProxy = lraProxy;
    }

    @Override
    public String toString() {
        return "LRAProxyConfigFile{" +
                "lraProxy=" + lraProxy +
                '}';
    }
}
