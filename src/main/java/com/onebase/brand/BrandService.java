package com.onebase.brand;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.brand.BrandDtos.BrandResponse;
import com.onebase.brand.BrandDtos.LookupResponse;
import com.onebase.brand.BrandDtos.SaveBrandRequest;
import com.onebase.client.ClientRepository;
import com.onebase.common.ApiException;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Brands tab: add from a link, edit, switch on and off.
 *
 * <p>Decided 2026-10-04: <b>no delete</b>. A brand that is switched off can't be
 * chosen for new clients or payments; nothing is removed, so history keeps it.
 *
 * <p>Two links to the same site are the same brand (see {@link BrandLinks}); the
 * database refuses a second one, and this says which brand already has it.
 *
 * <p>Every change is written to the Action log, saying what changed.
 */
@Service
public class BrandService {

	private static final Logger log = LoggerFactory.getLogger(BrandService.class);
	private static final String NOT_A_LOGO_URL = "The logo address must be a full web address, like https://nike.com/logo.png.";

	private final BrandRepository brands;
	private final BrandLookup lookup;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final ClientRepository clients;

	public BrandService(BrandRepository brands, BrandLookup lookup, UserRepository users, ActionLogService actionLog,
			ClientRepository clients) {
		this.brands = brands;
		this.lookup = lookup;
		this.users = users;
		this.actionLog = actionLog;
		this.clients = clients;
	}

	@Transactional(readOnly = true)
	public List<BrandResponse> list() {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : clients.countByBrand()) counts.put((Long) row[0], (Long) row[1]);
		return brands.findAllByOrderByCreatedAtAscIdAsc().stream()
			.map(brand -> BrandResponse.from(brand, counts.getOrDefault(brand.getId(), 0L)))
			.toList();
	}

	/** Reads the site. Saves nothing: the Admin checks the result first. */
	@Transactional(readOnly = true)
	public LookupResponse lookUp(String url) {
		BrandLinks.Website website = BrandLinks.website(url);
		String existing = brands.findByDomain(website.domain()).map(Brand::getName).orElse(null);
		BrandLookup.Result found;
		try {
			found = lookup.lookUp(website);
		} catch (UrlGuard.BlockedUrlException e) {
			throw ApiException.badRequest(e.getMessage());
		}
		return new LookupResponse(found.name(), website.domain(), website.url(), found.socials(), found.logoUrl(), existing,
			found.warning());
	}

	@Transactional
	public BrandResponse create(AuthPrincipal admin, SaveBrandRequest request) {
		BrandLinks.Website website = BrandLinks.website(request.websiteUrl());
		refuseTaken(website.domain(), null);
		Brand brand = new Brand(request.name(), website.domain(), website.url());
		brand.setSocials(BrandLinks.socials(request.socials()));
		brand.setLogoUrl(logoUrl(request.logoUrl()));
		if (request.ga4PropertyId() != null) brand.setGa4PropertyId(ga4Property(request.ga4PropertyId()));
		brand = saveRefusingDuplicates(brand);

		actionLog.record(actor(admin), Action.CREATED, TargetType.BRAND, brand.getId(), brand.getName(),
			"Added from " + website.domain());
		log.info("User id={} added brand id={} ({})", admin.userId(), brand.getId(), brand.getDomain());
		return BrandResponse.from(brand);
	}

	@Transactional
	public BrandResponse update(AuthPrincipal admin, long id, SaveBrandRequest request) {
		Brand brand = find(id);
		BrandLinks.Website website = BrandLinks.website(request.websiteUrl());
		refuseTaken(website.domain(), brand.getId());
		Map<String, String> socials = BrandLinks.socials(request.socials());

		List<String> changed = new ArrayList<>();
		if (!brand.getName().equals(request.name())) changed.add("name from " + brand.getName() + " to " + request.name());
		if (!brand.getWebsiteUrl().equals(website.url())) changed.add("website to " + website.domain());
		Map<String, String> before = brand.socials();
		for (String network : BrandLinks.NETWORKS) {
			if (!Objects.equals(before.get(network), socials.get(network))) changed.add(BrandLinks.label(network));
		}

		if (request.ga4PropertyId() != null) {
			String property = ga4Property(request.ga4PropertyId());
			if (!Objects.equals(property, brand.getGa4PropertyId())) {
				changed.add(property == null ? "GA4 property removed" : "GA4 property to " + property);
			}
			brand.setGa4PropertyId(property);
		}

		brand.setName(request.name());
		brand.setWebsite(website.domain(), website.url());
		brand.setSocials(socials);
		String logoUrl = logoUrl(request.logoUrl());
		if (!Objects.equals(logoUrl, brand.logoUrl())) changed.add(logoUrl == null ? "logo removed" : "logo");
		brand.setLogoUrl(logoUrl);
		saveRefusingDuplicates(brand);

		if (!changed.isEmpty()) {
			String detail = "Changed " + String.join(", ", changed) + (Boolean.TRUE.equals(request.fetchedFromSite()) ? " (fetched again from the site)" : "");
			actionLog.record(actor(admin), Action.UPDATED, TargetType.BRAND, brand.getId(), brand.getName(), detail);
		}
		return BrandResponse.from(brand);
	}

	/** "" = none; otherwise only digits (the number GA4 shows under Admin → Property details). */
	private static String ga4Property(String value) {
		if (value.isBlank()) return null;
		if (!value.matches("[0-9]{6,15}")) {
			throw ApiException.badRequest("The GA4 property ID is a number, like 412305881 (GA4 → Admin → Property details).");
		}
		return value;
	}

	@Transactional
	public BrandResponse setActive(AuthPrincipal admin, long id, boolean active) {
		Brand brand = find(id);
		if (brand.isActive() != active) {
			brand.setActive(active);
			actionLog.record(actor(admin), active ? Action.ACTIVATED : Action.DEACTIVATED, TargetType.BRAND,
				brand.getId(), brand.getName(),
				active ? "Brand switched on: it can be used for new clients" : "Brand switched off: it can't be used for new clients; nothing was deleted");
		}
		return BrandResponse.from(brand);
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private Brand find(long id) {
		return brands.findById(id).orElseThrow(() -> ApiException.notFound("This brand does not exist."));
	}

	private void refuseTaken(String domain, Long allowedId) {
		brands.findByDomain(domain)
			.filter(existing -> !existing.getId().equals(allowedId))
			.ifPresent(existing -> {
				throw ApiException.conflict(existing.getName() + " already uses " + domain + ".");
			});
	}

	/** Two Admins adding the same site at the same moment: the database has the last word. */
	private Brand saveRefusingDuplicates(Brand brand) {
		try {
			return brands.saveAndFlush(brand);
		} catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("Another brand already uses " + brand.getDomain() + ".");
		}
	}

	/**
	 * The logo's address as the form sent it: null for none, otherwise a full http(s)
	 * address — or 400. It is only kept, never opened by the server: browsers and
	 * mail apps load it from the brand's site.
	 */
	private static String logoUrl(String value) {
		if (value == null) return null;
		URI uri;
		try {
			uri = new URI(value);
		} catch (URISyntaxException e) {
			throw ApiException.badRequest(NOT_A_LOGO_URL);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getRawUserInfo() != null) {
			throw ApiException.badRequest(NOT_A_LOGO_URL);
		}
		return value;
	}

	private User actor(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}
}
