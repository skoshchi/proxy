package io.skoshchi.yaml;

import java.util.List;

public class LRAProxyConfig {
    private String url;
    private String lraCoordinatorUrl;

    private List<LRAProxyRouteConfig> lra;

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

    public String getLraCoordinatorUrl() {
        return lraCoordinatorUrl;

    }

    public void setLraCoordinatorUrl(String coordinatorUrl) {
        this.lraCoordinatorUrl = coordinatorUrl;
    }

    @Override
    public String toString() {
        return "LRAProxyConfig{" +
                "url='" + url + '\'' +
                ", coordinatorUrl='" + lraCoordinatorUrl + '\'' +
                ", lra=" + lra +
                '}';
    }
}
