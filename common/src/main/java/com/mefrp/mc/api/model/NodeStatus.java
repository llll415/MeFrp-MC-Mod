package com.mefrp.mc.api.model;

public class NodeStatus {
    public int nodeId;
    public String name = "";
    public int onlineClient;
    public int onlineProxy;
    public boolean isOnline;
    public String version = "";
    public long uptime;
    public int curConns;
    public int loadPercent = -1;
}
