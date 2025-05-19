package io.skoshchi.yaml;

import java.util.List;

public class LRAProxyConfig {
    private String url;

    private List<LRAProxyRouteConfig> lra;

    public LRAProxyConfig() {
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
        return "LRAProxyConfig{" +
                "url='" + url + '\'' +
                ", lra=" + lra +
                '}';
    }
}
