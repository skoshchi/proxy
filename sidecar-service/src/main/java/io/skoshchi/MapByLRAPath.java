package io.skoshchi;

import io.skoshchi.yaml.LRASettings;
import io.skoshchi.yaml.MethodType;

public class MapByLRAPath {
    private String serviceName;
    private String method;
    private LRASettings settings;
    private MethodType methodType;

    public MapByLRAPath(String serviceName, String method, LRASettings settings, MethodType methodType) {
        this.serviceName = serviceName;
        this.method = method;
        this.settings = settings;
        this.methodType = methodType;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public LRASettings getSettings() {
        return settings;
    }

    public void setSettings(LRASettings settings) {
        this.settings = settings;
    }

    public MethodType getMethodType() {
        return methodType;
    }

    public void setMethodType(MethodType methodType) {
        this.methodType = methodType;
    }
}
