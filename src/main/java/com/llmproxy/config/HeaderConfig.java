package com.llmproxy.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HeaderConfig {
    private boolean dropAll = false;
    private List<String> drop = new ArrayList<>();
    private Map<String, String> add = new HashMap<>();
    private Map<String, String> force = new HashMap<>();

    public boolean isDropAll() {
        return dropAll;
    }

    public void setDropAll(boolean dropAll) {
        this.dropAll = dropAll;
    }

    public List<String> getDrop() {
        return drop;
    }

    public void setDrop(List<String> drop) {
        this.drop = drop;
    }

    public Map<String, String> getAdd() {
        return add;
    }

    public void setAdd(Map<String, String> add) {
        this.add = add;
    }

    public Map<String, String> getForce() {
        return force;
    }

    public void setForce(Map<String, String> force) {
        this.force = force;
    }
}
