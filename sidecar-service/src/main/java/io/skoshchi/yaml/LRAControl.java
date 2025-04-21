package io.skoshchi.yaml;

public class LRAControl {
    private String name;
    private String path;
    private String method;
    private LRASettings lraSettings;
    private MethodType lraMethod;

    public LRAControl() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public LRASettings getLraSettings() {
        return lraSettings;
    }

    public void setLraSettings(LRASettings lraSettings) {
        this.lraSettings = lraSettings;
    }

    public MethodType getLraMethod() {
        return lraMethod;
    }

    public void setLraMethod(MethodType lraMethod) {
        this.lraMethod = lraMethod;
    }

    @Override
    public String toString() {
        return "LRAControls{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", method='" + method + '\'' +
                ", lraSettings=" + lraSettings +
                ", lraMethod=" + lraMethod +
                '}';
    }
}
