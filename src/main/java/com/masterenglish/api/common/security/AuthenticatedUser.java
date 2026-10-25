package com.masterenglish.api.common.security;

import java.util.UUID;

/** Shared principal view without dependencies on domain entities. */
public interface AuthenticatedUser {
    UUID getId();

    String getEmail();
}
