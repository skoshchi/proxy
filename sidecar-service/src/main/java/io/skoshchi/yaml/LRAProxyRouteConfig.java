package io.skoshchi.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LRAProxyRouteConfig {
    private String path;

    private String httpMethod;
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

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
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
                ", httpMethod='" + httpMethod + '\'' +
                ", lraSettings=" + lraSettings +
                ", lraMethod=" + lraMethod +
                '}';
    }
}
