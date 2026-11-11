package com.Goderium.BridgeNH.util;

public class BridgeException extends RuntimeException {
    public final String code;

    public BridgeException(String code, String message) {
        super(message);
        this.code = code;
    }
}
