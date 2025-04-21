package io.skoshchi.yaml;

import java.util.List;

public class LRAProxy {
    private String url;
    private String serviceName;

    private List<LRAControls> lraControls;
    private List<LRAMethods> lraMethods;


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

    public List<LRAControls> getLraControls() {
        return lraControls;
    }

    public void setLraControls(List<LRAControls> lraControls) {
        this.lraControls = lraControls;
    }

    public List<LRAMethods> getLraMethods() {
        return lraMethods;
    }

    public void setLraMethods(List<LRAMethods> lraMethods) {
        this.lraMethods = lraMethods;
    }

    @Override
    public String toString() {
        return "LRAProxyConfigFile{" +
                "url='" + url + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", lraControls=" + lraControls +
                ", lraMethods=" + lraMethods +
                '}';
    }
}
