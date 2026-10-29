package com.ims.common.security;

/** Roles carried in the JWT "role" claim. */
public final class Roles {
    public static final String CUSTOMER = "CUSTOMER";
    public static final String STORE_OPS = "STORE_OPS";
    public static final String RIDER = "RIDER";
    public static final String ADMIN = "ADMIN";

    private Roles() {
    }
}
