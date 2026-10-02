package com.onebase.security;

import com.onebase.user.UserRole;
import java.util.UUID;

/** Who is calling, as a controller sees it through {@code @AuthenticationPrincipal}. */
public record AuthPrincipal(long userId, String email, UserRole role, UUID sessionId) {
}
