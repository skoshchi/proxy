package io.skoshchi.yaml;

import java.util.List;

public class LRAProxyConfig {
    private String url;
    private String coordinatorUrl;

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

    public String getCoordinatorUrl() {
        return coordinatorUrl;
    }

    public void setCoordinatorUrl(String coordinatorUrl) {
        this.coordinatorUrl = coordinatorUrl;
    }

    @Override
    public String toString() {
        return "LRAProxyConfig{" +
                "url='" + url + '\'' +
                ", coordinatorUrl='" + coordinatorUrl + '\'' +
                ", lra=" + lra +
                '}';
    }
}
