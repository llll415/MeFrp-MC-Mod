package com.mefrp.mc.api.model;

import java.util.Locale;

public class UserInfo {
    public String email = "";
    public String friendlyGroup = "";
    public String group = "";
    public int inBound;
    public boolean isRealname;
    public int maxProxies;
    public int outBound;
    public long regTime;
    public int status;
    public boolean todaySigned;
    public long traffic;
    public int usedProxies;
    public int userId;
    public String username = "";

    public boolean canUseVipNodes() {
        String normalized = group == null ? "" : group.toLowerCase(Locale.ROOT);
        return normalized.equals("vip") || normalized.equals("sponsor") || normalized.equals("admin");
    }
}
