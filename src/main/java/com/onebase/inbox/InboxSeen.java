package com.onebase.inbox;

import com.onebase.common.ApiException;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The email Inbox's count in the sidebar (2026-10-28): brand mail that arrived since
 * this person last opened the Inbox. Per person, so one reading does not clear it
 * for the others; opening the Inbox clears it.
 */
@Service
public class InboxSeen {

	private final UserRepository users;
	private final InboxService inbox;

	public InboxSeen(UserRepository users, InboxService inbox) {
		this.users = users;
		this.inbox = inbox;
	}

	public record NewMail(int count) {
	}

	@Transactional(readOnly = true)
	public NewMail newMail(AuthPrincipal principal) {
		return new NewMail(inbox.newSince(user(principal).getInboxSeenAt()));
	}

	@Transactional
	public void seen(AuthPrincipal principal) {
		User user = user(principal);
		user.inboxSeen();
		users.save(user);
	}

	private User user(AuthPrincipal principal) {
		return users.findById(principal.userId()).orElseThrow(() -> ApiException.notFound("This account does not exist."));
	}
}
