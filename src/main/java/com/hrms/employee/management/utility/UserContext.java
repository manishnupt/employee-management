package com.hrms.employee.management.utility;

public class UserContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    public static String getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static void setCurrentUser(String user) {
        CURRENT_USER.set(user);
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
