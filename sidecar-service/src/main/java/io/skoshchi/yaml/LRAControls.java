package io.skoshchi.yaml;

public class LRAControls {
    private String name;
    private String path;
    private String method;
    private LRASettings lraSettings;

    public LRAControls() {
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

    @Override
    public String toString() {
        return "LRAControls{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", method='" + method + '\'' +
                ", lraSettings=" + lraSettings +
                '}';
    }
}
