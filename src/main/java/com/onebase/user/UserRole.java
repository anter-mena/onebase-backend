package com.onebase.user;

/**
 * The three roles the Users screen offers.
 *
 * <p>OWNER can do everything, ADMIN everything except billing, MANAGER clients
 * and renewals only. There is one OWNER, created by the bootstrap on an empty
 * database; invitations only hand out ADMIN or MANAGER.
 */
public enum UserRole {
	OWNER,
	ADMIN,
	MANAGER
}
