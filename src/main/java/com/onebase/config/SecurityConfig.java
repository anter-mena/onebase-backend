package com.onebase.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who can reach what.
 *
 * <p>Only the health check is public (the deploy pipeline and uptime checks need it).
 * Swagger and everything else sit behind HTTP Basic with the docs account, whose
 * credentials come from the environment (SWAGGER_USERNAME / SWAGGER_PASSWORD) and are
 * never in the repository. The app refuses to start if they are missing.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().authenticated())
			.httpBasic(Customizer.withDefaults())
			// Stateless API: no session cookie, so no CSRF token to protect.
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	UserDetailsService docsUser(
			@Value("${onebase.docs.username}") String username,
			@Value("${onebase.docs.password}") String password,
			PasswordEncoder encoder) {
		return new InMemoryUserDetailsManager(
			User.withUsername(username).password(encoder.encode(password)).roles("DOCS").build());
	}
}
