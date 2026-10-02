package com.onebase.security;

import com.onebase.auth.Session;
import com.onebase.auth.SessionRepository;
import com.onebase.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer …} and, if everything checks out, signs the
 * request in.
 *
 * <p>Four things must hold: the signature is ours and not expired, the session
 * it names exists and is still open, and its user may still sign in. Any
 * failure just leaves the request anonymous — the security rules then answer
 * 401 — so a bad token never reveals which check it failed.
 *
 * <p>⚠️ Not a {@code @Component} on purpose: Spring Boot registers every
 * {@code Filter} bean for all requests, which would run it outside the API
 * chain too. {@code SecurityConfig} creates it for the API chain only.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;
	private final SessionRepository sessions;

	public JwtAuthenticationFilter(JwtService jwtService, SessionRepository sessions) {
		this.jwtService = jwtService;
		this.sessions = sessions;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			authenticate(header.substring(7)).ifPresent(principal -> {
				var authentication = new UsernamePasswordAuthenticationToken(
					principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			});
		}
		chain.doFilter(request, response);
	}

	private Optional<AuthPrincipal> authenticate(String token) {
		try {
			Claims claims = jwtService.parse(token);
			UUID sessionId = UUID.fromString(claims.get(JwtService.CLAIM_SESSION, String.class));
			return sessions.findWithUserById(sessionId)
				.filter(Session::isOpen)
				.filter(session -> session.getUser().canSignIn())
				.filter(session -> Long.toString(session.getUser().getId()).equals(claims.getSubject()))
				.map(session -> {
					User user = session.getUser();
					return new AuthPrincipal(user.getId(), user.getEmail(), user.getRole(), session.getId());
				});
		} catch (JwtException | IllegalArgumentException | NullPointerException e) {
			return Optional.empty();
		}
	}
}
