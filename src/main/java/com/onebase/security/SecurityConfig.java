package com.onebase.security;

import com.onebase.auth.SessionRepository;
import com.onebase.common.ErrorResponse;
import com.onebase.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Who can reach what — two separate rule sets.
 *
 * <p><b>1. Swagger</b> (the docs): HTTP Basic with the docs account from
 * {@code SWAGGER_USERNAME}/{@code SWAGGER_PASSWORD}, exactly as before. The
 * browser's own login box is fine here; only you open these pages.
 *
 * <p><b>2. Everything else</b> (the API): signed-in users with a Bearer token.
 * Refusals are JSON in the shape the frontend reads, and there is deliberately
 * no {@code WWW-Authenticate: Basic} header — that header is what made
 * browsers pop up their grey login box on the site's own pages.
 *
 * <p>Public without a token: the health check, and the doors into an account —
 * sign in, "forgot password", setting a new one from the link, and opening or
 * accepting an invitation.
 *
 * <p><b>Roles</b> are read from the database on every request (see
 * {@code JwtAuthenticationFilter}), so switching someone off takes effect at once.
 * A COMMERCIAL gets only {@link #COMMERCIAL_API}; everything else needs ADMIN.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	private static final String[] DOCS = { "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**" };

	/**
	 * What a COMMERCIAL may call — mirrors the pages the frontend opens to them
	 * (lib/access.ts): Clients, Renewals, WhatsApp and the email Inbox, plus their
	 * own account. Everything not listed here is ADMIN-only.
	 */
	static final String[] COMMERCIAL_API = {
		"/api/auth/**",
		"/api/clients/**",
		"/api/renewals/**",
		"/api/whatsapp/**",
		"/api/inbox/**",
	};

	private final ObjectMapper objectMapper;

	public SecurityConfig(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Bean
	@Order(1)
	SecurityFilterChain docsChain(HttpSecurity http) throws Exception {
		http
			.securityMatcher(DOCS)
			.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("DOCS"))
			.httpBasic(Customizer.withDefaults())
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain apiChain(HttpSecurity http, JwtService jwtService, SessionRepository sessions,
			UserRepository users) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
				.requestMatchers(HttpMethod.POST,
					"/api/auth/login",
					"/api/auth/password/forgot",
					"/api/auth/password/reset",
					"/api/auth/password/reset/check",
					"/api/invitations/check",
					"/api/invitations/accept").permitAll()
				// Meta's webhook: no account, so it proves itself with a signature (see WhatsAppWebhookController).
				.requestMatchers("/api/whatsapp/webhook").permitAll()
				// A brand's logo is no secret, and the browser loads it as a plain image (no token to send).
				.requestMatchers(HttpMethod.GET, "/api/brands/*/logo").permitAll()
				// Reading the brand list: both roles (Commercials pick a brand for clients and payments).
				// Adding, editing and switching brands fall through to Admin-only below.
				.requestMatchers(HttpMethod.GET, "/api/brands").authenticated()
				// Reading the plans, perks and payment methods (the list): both roles. The
				// Add payment window prices a payment from them and picks the account it
				// was paid to; everyone sees its cost (decided 2026-10-06).
				// Changing them falls through to Admin-only below.
				.requestMatchers(HttpMethod.GET, "/api/plans", "/api/perks", "/api/payment-methods").authenticated()
				// Deleting a client: Admins only (adding and editing are open to both roles below).
				.requestMatchers(HttpMethod.DELETE, "/api/clients/**").hasRole("ADMIN")
				// Both roles: their own account, and the four areas a Commercial works in.
				.requestMatchers(COMMERCIAL_API).authenticated()
				// Everything else — Users, Configuration, Dashboard, SEO, Action log, and any
				// area added later — is Admin-only until someone deliberately opens it above.
				.anyRequest().hasRole("ADMIN"))
			// Stateless API: no session cookie, so no CSRF token to protect.
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> writeError(response, 401, "Unauthorized",
					"Please sign in to continue.", request.getRequestURI()))
				.accessDeniedHandler((request, response, e) -> writeError(response, 403, "Forbidden",
					"You do not have permission to do that.", request.getRequestURI())))
			.addFilterBefore(new JwtAuthenticationFilter(jwtService, sessions, users), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** The Swagger account. Only the docs chain uses it; the API has no password login of this kind. */
	@Bean
	UserDetailsService docsUser(
			@Value("${onebase.docs.username}") String username,
			@Value("${onebase.docs.password}") String password,
			PasswordEncoder encoder) {
		return new InMemoryUserDetailsManager(
			User.withUsername(username).password(encoder.encode(password)).roles("DOCS").build());
	}

	private void writeError(HttpServletResponse response, int status, String error, String message, String path)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(status, error, message, path));
	}
}
