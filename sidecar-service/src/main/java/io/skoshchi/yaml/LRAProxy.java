package io.skoshchi.yaml;

import java.util.List;

public class LRAProxy {
    private String url;
    private String serviceName;

    private List<LRAControl> lraControls;

    public LRAProxy() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<LRAControl> getLraControls() {
        return lraControls;
    }

    public void setLraControls(List<LRAControl> lraControls) {
        this.lraControls = lraControls;
    }


    @Override
    public String toString() {
        return "LRAProxyConfigFile{" +
                "url='" + url + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", lraControls=" + lraControls +
                '}';
    }
}
