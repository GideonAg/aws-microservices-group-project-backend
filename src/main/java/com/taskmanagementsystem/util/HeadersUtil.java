package com.taskmanagementsystem.util;

import java.util.Map;

public class HeadersUtil {

    public static Map<String, String> getHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS",
                "Access-Control-Allow-Headers", "Content-Type, Authorization"
        );
    }
}
