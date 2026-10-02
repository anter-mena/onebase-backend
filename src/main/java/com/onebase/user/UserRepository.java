package com.onebase.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	/** Emails are stored lowercase, so callers must lowercase before asking. */
	Optional<User> findByEmail(String email);

	List<User> findByEmailIn(Collection<String> emails);

}
