package com.lumisight.common.util;

public final class ValueParsers {

    private ValueParsers() {
    }

    public static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
