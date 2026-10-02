package com.onebase.config;

import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first account — an ADMIN — on an empty database.
 *
 * <p>The settings keep their original {@code BOOTSTRAP_OWNER_*} names (the server
 * already has them, and they are read once, ever). Before the switch to two
 * roles this account was the OWNER; migration V2 made it an ADMIN.
 *
 * <p>The LMS lesson: its first version created accounts with passwords written
 * in the source, and anyone reading the public repo was an administrator. Here
 * the credentials come only from {@code BOOTSTRAP_OWNER_*} environment
 * variables, are read only while the users table is completely empty, and are
 * ignored forever after. Everyone else joins by invitation.
 */
@Component
public class BootstrapOwner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapOwner.class);

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final String name;
	private final String email;
	private final String password;

	public BootstrapOwner(UserRepository users, PasswordEncoder passwordEncoder,
			@Value("${onebase.bootstrap.owner-name:Owner}") String name,
			@Value("${onebase.bootstrap.owner-email:}") String email,
			@Value("${onebase.bootstrap.owner-password:}") String password) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.name = name;
		this.email = email;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		if (email.isBlank() || password.isBlank()) {
			log.warn("No accounts exist and BOOTSTRAP_OWNER_EMAIL / BOOTSTRAP_OWNER_PASSWORD are not set — nobody can sign in.");
			return;
		}
		if (password.length() < 8) {
			log.error("BOOTSTRAP_OWNER_PASSWORD is shorter than 8 characters — first admin not created.");
			return;
		}
		users.save(new User(name.trim(), email.trim().toLowerCase(), passwordEncoder.encode(password), UserRole.ADMIN));
		log.info("Created the first admin {} — the BOOTSTRAP_OWNER_* variables are now ignored.", email.trim().toLowerCase());
	}
}
