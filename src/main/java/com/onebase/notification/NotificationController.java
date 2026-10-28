package com.onebase.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The sidebar's counts: both roles (SecurityConfig.COMMERCIAL_API). */
@RestController
@Tag(name = "Notifications", description = "What is waiting in Clients, Renewals and WhatsApp, for the sidebar.")
public class NotificationController {

	private final NotificationService service;

	public NotificationController(NotificationService service) {
		this.service = service;
	}

	@GetMapping("/api/notifications")
	@Operation(summary = "What is waiting in each area", description = "clients: still New. renewals: ending within 10 days or due a callback. "
		+ "whatsapp: unread messages. The Email Inbox counts its own (GET /api/inbox/overview).")
	public NotificationService.Counts counts() {
		return service.counts();
	}
}
