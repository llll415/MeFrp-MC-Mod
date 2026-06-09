package com.mefrp.mc.api.model;

public class Node {
    public int nodeId;
    public String name = "";
    public String hostname = "";
    public String description = "";
    public String allowGroup = "";
    public String allowPort = "";
    public String allowType = "";
    public String region = "";
    public String bandwidth = "";
    public boolean isOnline;
    public boolean isDisabled;

    public boolean supportsTcp() {
        return allowType == null || allowType.isEmpty() || allowType.contains("tcp");
    }

    public boolean isVip() {
        String groups = ";" + (allowGroup == null ? "" : allowGroup.toLowerCase(java.util.Locale.ROOT)) + ";";
        return groups.contains(";vip;") && !groups.contains(";default;");
    }
}
