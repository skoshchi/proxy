package io.skoshchi.interfece;

public class ProxyYamlConfigStructure {
    private String startLRA;
    private String completeLRA;
    private String serviceURL;

    public ProxyYamlConfigStructure() {
    }

    public String getServiceURL() {
        return serviceURL;
    }

    public void setServiceURL(String serviceURL) {
        this.serviceURL = serviceURL;
    }

    public String getStartLRA() {
        return startLRA;
    }

    public void setStartLRA(String startLRA) {
        this.startLRA = startLRA;
    }

    public String getCompleteLRA() {
        return completeLRA;
    }

    public void setCompleteLRA(String completeLRA) {
        this.completeLRA = completeLRA;
    }
}
