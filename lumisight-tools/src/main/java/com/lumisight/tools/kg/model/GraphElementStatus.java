package com.lumisight.tools.kg.model;

public enum GraphElementStatus {
    ACTIVE(0),
    DELETED(1);

    private final int code;

    GraphElementStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
