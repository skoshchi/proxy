package io.skoshchi;

import io.skoshchi.yaml.LRASettings;
import io.skoshchi.yaml.LRAMethodType;
import jakarta.ws.rs.HttpMethod;

// record
public class LRARoute {
    private String httpMethod;
    private LRASettings settings;
    private LRAMethodType methodType;

    public LRARoute(String httpMethod, LRASettings settings, LRAMethodType methodType) {
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

    public LRAMethodType getMethodType() {
        return methodType;
    }

    public void setMethodType(LRAMethodType methodType) {
        this.methodType = methodType;
    }
}
