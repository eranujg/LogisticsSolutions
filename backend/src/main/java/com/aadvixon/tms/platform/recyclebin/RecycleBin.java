package com.aadvixon.tms.platform.recyclebin;

import java.util.Map;

/**
 * Record types that support soft delete. Table and label column names come only
 * from this fixed list, never from user input.
 */
enum RecycleBin {
    COMPANY("company", "legal_name"),
    LOCATION("location", "code || ' - ' || name"),
    CITY_SERVICE("city_service", "id::text"),
    APP_USER("app_user", "full_name"),
    ROLE("role", "name"),
    PARTY("party", "code || ' - ' || legal_name"),
    RATE_CARD("rate_card", "code || ' v' || version || ' - ' || name"),
    CHARGE_HEAD("charge_head", "code || ' - ' || name");

    private static final Map<String, RecycleBin> BY_PATH = Map.of(
            "companies", COMPANY,
            "locations", LOCATION,
            "city-services", CITY_SERVICE,
            "users", APP_USER,
            "roles", ROLE,
            "parties", PARTY,
            "rate-cards", RATE_CARD,
            "charge-heads", CHARGE_HEAD);

    final String table;
    final String labelExpression;

    RecycleBin(String table, String labelExpression) {
        this.table = table;
        this.labelExpression = labelExpression;
    }

    static RecycleBin fromPath(String type) {
        RecycleBin bin = BY_PATH.get(type);
        if (bin == null) {
            throw new IllegalArgumentException("Unknown record type: " + type + ". Allowed: " + BY_PATH.keySet());
        }
        return bin;
    }
}
