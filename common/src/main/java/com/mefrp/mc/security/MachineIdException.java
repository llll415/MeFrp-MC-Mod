package com.mefrp.mc.security;

public class MachineIdException extends Exception {
    private static final long serialVersionUID = 1L;

    public MachineIdException(String message) {
        super(message);
    }

    public MachineIdException(String message, Throwable cause) {
        super(message, cause);
    }
}
