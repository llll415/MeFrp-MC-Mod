package com.mefrp.mc.security;

public interface MachineIdProvider {
    String getMachineId() throws MachineIdException;
}
