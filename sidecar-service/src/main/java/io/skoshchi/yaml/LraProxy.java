package io.skoshchi.yaml;

public class LraProxy {
    private String url;
    private String serviceName;
    private Operation start;
    private Operation complete;

    public LraProxy() {
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

    public Operation getStart() {
        return start;
    }

    public void setStart(Operation start) {
        this.start = start;
    }

    public Operation getComplete() {
        return complete;
    }

    public void setComplete(Operation complete) {
        this.complete = complete;
    }
}
