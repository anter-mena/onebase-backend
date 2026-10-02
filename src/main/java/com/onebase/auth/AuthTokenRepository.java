package com.onebase.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

	@EntityGraph(attributePaths = "user")
	Optional<AuthToken> findByTokenHashAndType(String tokenHash, AuthToken.Type type);

	List<AuthToken> findByUserIdAndTypeAndUsedAtIsNull(Long userId, AuthToken.Type type);
}
