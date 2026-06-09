package com.mefrp.mc.api.model;

import java.util.ArrayList;
import java.util.List;

public class ProxyList {
    public final List<Node> nodes = new ArrayList<>();
    public final List<Proxy> proxies = new ArrayList<>();

    public Node findNode(int nodeId) {
        for (Node node : nodes) {
            if (node.nodeId == nodeId) {
                return node;
            }
        }
        return null;
    }
}
