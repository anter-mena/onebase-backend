package com.onebase.credit;

import com.onebase.client.ClientRepository;
import com.onebase.mail.MailService;
import com.onebase.payment.Payment;
import com.onebase.payment.PaymentRepository;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails the "panel credit is running low" warning (2026-10-28) to ALERT_EMAILS, once the
 * payment that crossed the mark is saved: a payment that fails sends nothing. In the
 * background, so Gmail never slows the Add payment window; a failure is logged, never
 * shown to whoever added the payment.
 */
@Component
public class LowCreditAlerts {

	private static final Logger log = LoggerFactory.getLogger(LowCreditAlerts.class);

	private final PaymentRepository payments;
	private final CreditTopupRepository topups;
	private final ClientRepository clients;
	private final UserRepository users;
	private final MailService mail;
	private final List<String> recipients;
	private final String appUrl;
	private final ZoneId zone;

	public LowCreditAlerts(PaymentRepository payments, CreditTopupRepository topups, ClientRepository clients, UserRepository users,
			MailService mail, @Value("${onebase.alerts.emails:}") String recipients, @Value("${onebase.frontend-url}") String appUrl,
			@Value("${onebase.timezone:America/Toronto}") String zone) {
		this.payments = payments;
		this.topups = topups;
		this.clients = clients;
		this.users = users;
		this.mail = mail;
		this.recipients = Arrays.stream(recipients.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		this.appUrl = appUrl;
		this.zone = ZoneId.of(zone);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(LowCredit event) {
		if (recipients.isEmpty()) {
			log.warn("Panel credit down to {} (warning at {}), but ALERT_EMAILS is empty: no email sent", event.left(), event.threshold());
			return;
		}
		CompletableFuture.runAsync(() -> send(event)).exceptionally(e -> {
			log.error("Could not send the low panel credit warning", e);
			return null;
		});
	}

	private void send(LowCredit event) {
		Payment payment = payments.findById(event.paymentId()).orElseThrow();
		String client = clients.findById(payment.getClientId()).map(c -> c.displayName()).orElse("A client");
		String addedBy = payment.getCreatedBy() == null ? "Someone"
			: users.findById(payment.getCreatedBy()).map(User::getFullName).orElse("Someone");
		String plan = payment.getMonths() + (payment.getMonths() == 1 ? " month" : " months") + " · " + payment.getDevices()
			+ (payment.getDevices() == 1 ? " device" : " devices");
		Instant now = Instant.now();
		long usedLastWeek = payments.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
			now.minus(Duration.ofDays(7)), now.plusSeconds(1)).stream().mapToLong(Payment::getCreditsUsed).sum();
		CreditTopup last = topups.findFirstByOrderByCreatedAtDescIdDesc().orElse(null);

		LowCreditEmail.Facts facts = new LowCreditEmail.Facts(event.left(), event.threshold(), client, plan, payment.getCreditsUsed(), addedBy,
			usedLastWeek, last == null ? null : last.getCreatedAt().atZone(zone).toLocalDate(), last == null ? 0 : last.getCredits());
		mail.sendAlert(recipients, LowCreditEmail.subject(facts), LowCreditEmail.text(facts, appUrl), LowCreditEmail.html(facts, appUrl));
		log.info("Low panel credit warning sent ({} credits left) to {} recipients", event.left(), recipients.size());
	}
}
