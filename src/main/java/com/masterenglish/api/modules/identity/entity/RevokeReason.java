package com.masterenglish.api.modules.identity.entity;

public enum RevokeReason {
    LOGOUT,
    PASSWORD_RESET,
    PASSWORD_CHANGE,
    SECURITY,
    ROLE_CHANGE,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    SESSION_LIMIT,
    EXPIRED
}
