package com.onebase.client;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.client.Client.Status;
import com.onebase.client.ClientDtos.ClientResponse;
import com.onebase.client.ClientDtos.SaveClientRequest;
import com.onebase.common.ApiException;
import com.onebase.payment.Payment;
import com.onebase.payment.PaymentRepository;
import com.onebase.paymentmethod.PaymentMethod;
import com.onebase.paymentmethod.PaymentMethodRepository;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.whatsapp.WhatsAppConversation;
import com.onebase.whatsapp.WhatsAppConversationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Clients module: the list, one client, edit, the note, delete — and the
 * client WhatsApp makes when an unknown number writes, which is the only way a
 * client is made (decided 2026-10-06: no Add client).
 *
 * <p><b>One client per number.</b> The phone is stored as E.164 and is unique,
 * deleted clients included: a deleted client who writes again comes back rather
 * than being made twice. The client's id stays the number every payment points at.
 *
 * <p>Admins and Commercials both edit; only Admins delete (SecurityConfig).
 * Delete is soft, so past payments keep counting. Every change by a person is
 * written to the Action log; the rows WhatsApp makes say so in their source.
 */
@Service
public class ClientService {

	private static final Logger log = LoggerFactory.getLogger(ClientService.class);
	static final String NEEDS_NAME = "Enter the client's full name.";
	static final String NEEDS_CONTACT = "Enter a phone number or an email address.";

	private final ClientRepository clients;
	private final BrandRepository brands;
	private final WhatsAppConversationRepository conversations;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final PaymentRepository payments;
	private final PaymentMethodRepository methods;
	private final ZoneId zone;

	public ClientService(ClientRepository clients, BrandRepository brands, WhatsAppConversationRepository conversations,
			UserRepository users, ActionLogService actionLog, PaymentRepository payments, PaymentMethodRepository methods,
			@Value("${onebase.timezone:America/Toronto}") String zone) {
		this.clients = clients;
		this.brands = brands;
		this.conversations = conversations;
		this.users = users;
		this.actionLog = actionLog;
		this.payments = payments;
		this.methods = methods;
		this.zone = ZoneId.of(zone);
	}

	// ── The screens ───────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<ClientResponse> list() {
		return views(clients.findByDeletedAtIsNullOrderByCreatedAtDescIdDesc());
	}

	@Transactional(readOnly = true)
	public ClientResponse get(long id) {
		return views(List.of(find(id))).getFirst();
	}

	@Transactional
	public ClientResponse update(AuthPrincipal principal, long id, SaveClientRequest request) {
		Client client = find(id);
		// A WhatsApp client may keep going by their WhatsApp name.
		if (request.fullName() == null && client.getUsername() == null) throw ApiException.badRequest(NEEDS_NAME);
		String phone = PhoneNumbers.normalize(request.phone());
		if (phone == null && request.email() == null) throw ApiException.badRequest(NEEDS_CONTACT);
		refuseTaken(phone, client.getId());
		Long brandId = usableBrand(request.brandId(), client.getBrandId());
		// Only Trial, Pending and Drop are chosen by a person; keeping the current one is fine.
		if (request.status() != client.getStatus() && !request.status().manual()) {
			throw ApiException.badRequest(label(request.status()) + " is set by One Base: choose Trial, Pending or Drop.");
		}

		List<String> changed = new ArrayList<>();
		if (!Objects.equals(client.getFullName(), request.fullName())) changed.add("full name");
		if (!Objects.equals(client.getPhone(), phone)) changed.add("phone");
		if (!Objects.equals(client.getEmail(), request.email())) changed.add("email");
		if (!Objects.equals(client.getBrandId(), brandId)) {
			changed.add("brand from " + brandName(client.getBrandId()) + " to " + brandName(brandId));
		}
		if (client.getStatus() != request.status()) {
			changed.add("status from " + label(client.getStatus()) + " to " + label(request.status()));
		}
		if (changed.isEmpty()) return get(id);

		boolean phoneChanged = !Objects.equals(client.getPhone(), phone);
		client.update(request.fullName(), request.email(), phone,
			phoneChanged ? PhoneNumbers.country(phone) : client.getCountry(), brandId, request.status());
		save(client);
		if (phoneChanged) relinkConversation(client);
		actionLog.record(actor(principal), Action.UPDATED, TargetType.CLIENT, client.getId(), client.displayName(),
			"Changed " + String.join(", ", changed));
		return get(id);
	}

	@Transactional
	public ClientResponse setNote(AuthPrincipal principal, long id, String note) {
		Client client = find(id);
		if (Objects.equals(client.getNote(), note)) return get(id);
		client.setNote(note);
		clients.save(client);
		actionLog.record(actor(principal), Action.UPDATED, TargetType.CLIENT, client.getId(), client.displayName(),
			note == null ? "Removed the note" : "Changed the note");
		return get(id);
	}

	@Transactional
	public void delete(AuthPrincipal principal, long id) {
		Client client = find(id);
		client.delete();
		clients.save(client);
		actionLog.record(actor(principal), Action.DELETED, TargetType.CLIENT, client.getId(), client.displayName(),
			"Deleted the client; their past payments still count");
		log.info("User id={} deleted client id={}", principal.userId(), client.getId());
	}

	// ── Renewals ──────────────────────────────────────────────────────────────

	/** Days before the end that a client shows in Renewals. */
	static final long ENDING_SOON_DAYS = 10;

	/**
	 * The people to follow up (decided 2026-10-06): Active with 10 days or less left
	 * (or already past, before the hourly job catches up), every Callback, every
	 * Pending, every Inactive. Soonest first within each group.
	 */
	@Transactional(readOnly = true)
	public List<ClientDtos.RenewalRow> renewals() {
		LocalDate today = LocalDate.now(zone);
		List<ClientDtos.RenewalRow> rows = new ArrayList<>();
		for (ClientResponse client : list()) {
			Long daysLeft = client.subscriptionEnd() == null ? null
				: java.time.temporal.ChronoUnit.DAYS.between(today, client.subscriptionEnd());
			String group = switch (client.status()) {
				case ACTIVE -> daysLeft != null && daysLeft <= ENDING_SOON_DAYS ? "ENDING_SOON" : null;
				case CALLBACK -> "CALLBACK";
				case PENDING -> "PENDING";
				case INACTIVE -> "INACTIVE";
				default -> null;
			};
			if (group != null) rows.add(new ClientDtos.RenewalRow(client, group, daysLeft));
		}
		rows.sort(Comparator.comparing((ClientDtos.RenewalRow row) -> row.daysLeft() == null ? Long.MAX_VALUE : row.daysLeft()));
		return rows;
	}

	// ── WhatsApp ──────────────────────────────────────────────────────────────

	/**
	 * A message from {@code waId}: the client with that number, made now (New, no
	 * brand, named after the profile) if there is none. A deleted client comes back;
	 * the WhatsApp name follows the profile. Null if the number can't be read.
	 */
	@Transactional
	public Long fromWhatsApp(String waId, String profileName) {
		String phone = PhoneNumbers.fromWhatsApp(waId);
		if (phone == null) return null;
		String username = profileName == null || profileName.isBlank() ? null : profileName.strip();
		if (username != null && username.length() > 120) username = username.substring(0, 120);
		Client existing = clients.findByPhone(phone).orElse(null);
		if (existing != null) {
			if (existing.seenOnWhatsApp(username)) clients.save(existing);
			return existing.getId();
		}
		Client client = clients.saveAndFlush(Client.fromWhatsApp(phone, PhoneNumbers.country(phone), username));
		log.info("WhatsApp number {} wrote for the first time: client id={} made", maskTail(phone), client.getId());
		return client.getId();
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private List<ClientResponse> views(Collection<Client> rows) {
		List<Long> brandIds = rows.stream().map(Client::getBrandId).filter(Objects::nonNull).distinct().toList();
		Map<Long, Brand> brandsById = brands.findAllById(brandIds).stream()
			.collect(Collectors.toMap(Brand::getId, Function.identity()));
		List<Long> ids = rows.stream().map(Client::getId).toList();
		Map<Long, Long> conversationByClient = new HashMap<>();
		for (WhatsAppConversation c : conversations.findByClientIdIn(ids)) conversationByClient.putIfAbsent(c.getClientId(), c.getId());
		// Orders, revenue and the current plan: from the payments, never stored.
		Map<Long, List<Payment>> paymentsByClient = payments.findByClientIdInAndDeletedAtIsNull(ids).stream()
			.collect(Collectors.groupingBy(Payment::getClientId));
		Map<Long, PaymentMethod> methodsById = methods.findAll().stream()
			.collect(Collectors.toMap(PaymentMethod::getId, Function.identity()));
		LocalDate today = LocalDate.now(zone);

		return rows.stream().map(client -> {
			Brand brand = client.getBrandId() == null ? null : brandsById.get(client.getBrandId());
			String logoUrl = brand == null ? null : brand.logoUrl();
			List<Payment> paid = paymentsByClient.getOrDefault(client.getId(), List.of());
			Payment latest = paid.stream()
				.max(Comparator.comparing(Payment::getCreatedAt).thenComparing(Payment::getId)).orElse(null);
			PaymentMethod method = latest == null ? null : methodsById.get(latest.getPaymentMethodId());
			BigDecimal revenue = paid.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
			LocalDate end = paid.stream().map(Payment::getEndsOn).max(Comparator.naturalOrder()).orElse(null);
			return new ClientResponse(client.getId(), client.displayName(), client.getFullName(), client.getUsername(),
				client.getEmail(), client.getPhone(), client.getCountry(), client.getBrandId(),
				brand == null ? null : brand.getName(), logoUrl, client.getStatus(), client.getSource(), client.getNote(),
				conversationByClient.get(client.getId()), client.getCreatedAt(), client.getUpdatedAt(),
				paid.size(), revenue,
				latest == null ? null : latest.getDevices(), latest == null ? null : latest.getMonths(),
				latest == null ? null : latest.getStartsOn(), end,
				method == null ? null : method.getProvider().name(), method == null ? null : method.getName(),
				quarterTrend(paid, today), client.getStatusChangedAt());
		}).toList();
	}

	/** Payments in each of the last four calendar quarters, oldest first — the Orders sparkline. */
	private List<Integer> quarterTrend(List<Payment> paid, LocalDate today) {
		int[] counts = new int[4];
		int thisQuarter = today.getYear() * 4 + (today.getMonthValue() - 1) / 3;
		for (Payment payment : paid) {
			LocalDate day = payment.getCreatedAt().atZone(zone).toLocalDate();
			int back = thisQuarter - (day.getYear() * 4 + (day.getMonthValue() - 1) / 3);
			if (back >= 0 && back < 4) counts[3 - back]++;
		}
		return List.of(counts[0], counts[1], counts[2], counts[3]);
	}

	private Client find(long id) {
		return clients.findById(id).filter(client -> !client.isDeleted())
			.orElseThrow(() -> ApiException.notFound("This client does not exist."));
	}

	private void refuseTaken(String phone, Long allowedId) {
		if (phone == null) return;
		clients.findByPhone(phone).filter(other -> !other.getId().equals(allowedId)).ifPresent(other -> {
			throw ApiException.conflict(other.isDeleted()
				? "This number belongs to a deleted client (#" + other.getId() + ")."
				: "This number already belongs to " + other.displayName() + " (#" + other.getId() + ").");
		});
	}

	/** A brand being chosen must be switched on; one the client already has may stay. */
	private Long usableBrand(Long brandId, Long currentBrandId) {
		if (brandId == null) return null;
		Brand brand = brands.findById(brandId).orElseThrow(() -> ApiException.badRequest("This brand does not exist."));
		if (!brand.isActive() && !brandId.equals(currentBrandId)) {
			throw ApiException.badRequest(brand.getName() + " is switched off: it can't be chosen for clients.");
		}
		return brandId;
	}

	/** Two people saving the same number at the same moment: the database has the last word. */
	private Client save(Client client) {
		try {
			return clients.saveAndFlush(client);
		} catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("This number already belongs to another client.");
		}
	}

	/** A client whose number changed to one that already writes on WhatsApp gets that conversation. */
	private void linkConversation(Client client) {
		if (client.getPhone() == null) return;
		conversations.findByWaId(client.getPhone().substring(1)).ifPresent(conversation -> {
			if (conversation.getClientId() == null) {
				conversation.linkClient(client.getId());
				conversations.save(conversation);
			}
		});
	}

	/** New number: the old conversation is no longer theirs; the new number's one is. */
	private void relinkConversation(Client client) {
		for (WhatsAppConversation conversation : conversations.findByClientIdIn(List.of(client.getId()))) {
			conversation.linkClient(null);
			conversations.save(conversation);
		}
		linkConversation(client);
	}

	private String brandName(Long brandId) {
		if (brandId == null) return "no brand";
		return brands.findById(brandId).map(Brand::getName).orElse("#" + brandId);
	}

	private User actor(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}

	static String label(Status status) {
		String lower = status.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/** Logs show only the end of a number. */
	private static String maskTail(String phone) {
		return phone.length() <= 4 ? "****" : "…" + phone.substring(phone.length() - 4);
	}
}
