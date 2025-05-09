package io.skoshchi.yaml;

import java.util.List;

public class LRAProxySettings {
    private String url;

    private List<LRAProxyRouteConfig> lra;

    public LRAProxySettings() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<LRAProxyRouteConfig> getLra() {
        return lra;
    }

    public void setLra(List<LRAProxyRouteConfig> lra) {
        this.lra = lra;
    }

    @Override
    public String toString() {
        return "Proxy{" +
                "url='" + url + '\'' +
                ", lra=" + lra +
                '}';
    }
}
