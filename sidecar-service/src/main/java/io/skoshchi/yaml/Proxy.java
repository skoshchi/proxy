package io.skoshchi.yaml;

import java.util.List;

public class Proxy {
    private String url;
    private String service;

    private List<LRAProxy> lra;

    public Proxy() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public List<LRAProxy> getLra() {
        return lra;
    }

    public void setLra(List<LRAProxy> lra) {
        this.lra = lra;
    }

    @Override
    public String toString() {
        return "Proxy{" +
                "url='" + url + '\'' +
                ", service='" + service + '\'' +
                ", lra=" + lra +
                '}';
    }
}
