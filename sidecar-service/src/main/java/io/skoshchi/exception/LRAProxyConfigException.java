package io.skoshchi.exception;


public class LRAProxyConfigException extends RuntimeException {

    public LRAProxyConfigException(String message) {
        super(message);
    }

    public LRAProxyConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}