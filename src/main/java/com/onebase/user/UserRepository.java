package com.onebase.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

	/** Emails are stored lowercase, so callers must lowercase before asking. */
	Optional<User> findByEmail(String email);

	List<User> findByEmailIn(Collection<String> emails);

	/**
	 * "Last active" on the Users screen: stamped on a signed-in request, at most
	 * once per {@code since} window, so a busy page does not write on every call.
	 */
	@Transactional
	@Modifying
	@Query("update User u set u.lastActiveAt = :now where u.id = :id and (u.lastActiveAt is null or u.lastActiveAt < :since)")
	int touchLastActive(Long id, Instant now, Instant since);
}
