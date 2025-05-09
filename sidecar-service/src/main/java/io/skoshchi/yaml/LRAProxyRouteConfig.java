package io.skoshchi.yaml;

public class LRAProxyRouteConfig {
    private String path;
    private String method;
    private LRASettings lraSettings;
    private MethodType lraMethod;

    public LRAProxyRouteConfig() {
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public LRASettings getLraSettings() {
        return lraSettings;
    }

    public void setLraSettings(LRASettings lraSettings) {
        this.lraSettings = lraSettings;
    }

    public MethodType getLraMethod() {
        return lraMethod;
    }

    public void setLraMethod(MethodType lraMethod) {
        this.lraMethod = lraMethod;
    }

    @Override
    public String toString() {
        return "LRAProxy{" +
                "path='" + path + '\'' +
                ", method='" + method + '\'' +
                ", lraSettings=" + lraSettings +
                ", lraMethod=" + lraMethod +
                '}';
    }
}
