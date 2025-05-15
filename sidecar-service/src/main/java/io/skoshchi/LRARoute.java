package io.skoshchi;

import io.skoshchi.yaml.LRASettings;
import io.skoshchi.yaml.MethodType;

// record
public class LRARoute {
    private String httpMethod;
    private LRASettings settings;
    private MethodType methodType;

    public LRARoute(String httpMethod, LRASettings settings, MethodType methodType) {
        this.httpMethod = httpMethod;
        this.settings = settings;
        this.methodType = methodType;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
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
