package io.skoshchi.yaml;

public class LRAMethods {
    public enum MethodType {
        Compensate,
        Complete,
        Status,
        Forget,
        AfterLRA
    }

    private String name;
    private String path;
    private String method;
    private MethodType lraMethod;


    public LRAMethods() {
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

    public MethodType getLraMethod() {
        return lraMethod;
    }

    public void setLraMethod(MethodType lraMethod) {
        this.lraMethod = lraMethod;
    }

    @Override
    public String toString() {
        return "LRAMethods{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", method='" + method + '\'' +
                ", lraMethod=" + lraMethod +
                '}';
    }
}
