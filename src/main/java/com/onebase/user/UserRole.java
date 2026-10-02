package com.onebase.user;

/**
 * The two roles, with fixed privileges (see {@code SecurityConfig} for the rules).
 *
 * <p>ADMIN can do everything; there can be several, and no Admin can ever be
 * switched off — so there is never zero. COMMERCIAL works with Clients, Renewals,
 * WhatsApp and the email Inbox, and is refused everything else.
 */
public enum UserRole {
	ADMIN,
	COMMERCIAL
}
