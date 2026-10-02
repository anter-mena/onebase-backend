package com.onebase.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessionRepository extends JpaRepository<Session, UUID> {

	/** The session and its user in one query — this runs on every authenticated request. */
	@EntityGraph(attributePaths = "user")
	Optional<Session> findWithUserById(UUID id);

	/** Ends every open session of a user — after a password reset, nobody should stay signed in. */
	@Modifying
	@Query("update Session s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
	int revokeAll(Long userId, Instant now);

	/** Ends every open session except one — the device that just changed the password stays in. */
	@Modifying
	@Query("update Session s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null and s.id <> :keep")
	int revokeAllExcept(Long userId, UUID keep, Instant now);
}
