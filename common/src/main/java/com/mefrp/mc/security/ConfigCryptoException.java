package com.mefrp.mc.security;

public class ConfigCryptoException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigCryptoException(String message) {
        super(message);
    }

    public ConfigCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
