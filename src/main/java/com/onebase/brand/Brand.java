package com.onebase.brand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A brand (table {@code brands}). There is no delete: a brand is switched off,
 * so clients and payments keep pointing at it.
 *
 * <p>The logo is not stored (decided 2026-10-16): only its address on the
 * brand's own website, which every screen and email loads it from.
 */
@Entity
@Table(name = "brands")
public class Brand {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true)
	private String domain;

	@Column(name = "website_url", nullable = false)
	private String websiteUrl;

	@Column(name = "instagram_url")
	private String instagramUrl;

	@Column(name = "facebook_url")
	private String facebookUrl;

	@Column(name = "x_url")
	private String xUrl;

	@Column(name = "tiktok_url")
	private String tiktokUrl;

	/** The logo's absolute http(s) address on the brand's site. Null = none (the initials show). */
	@Column(name = "logo_url", length = 2000)
	private String logoUrl;

	/** The brand's Google Analytics 4 property number, for the SEO page. Null = none. */
	@Column(name = "ga4_property_id")
	private String ga4PropertyId;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Brand() {
	}

	Brand(String name, String domain, String websiteUrl) {
		this.name = name;
		this.domain = domain;
		this.websiteUrl = websiteUrl;
	}

	/** The four social links, by network; missing ones are left out. */
	Map<String, String> socials() {
		Map<String, String> socials = new LinkedHashMap<>();
		if (instagramUrl != null) socials.put("instagram", instagramUrl);
		if (facebookUrl != null) socials.put("facebook", facebookUrl);
		if (xUrl != null) socials.put("x", xUrl);
		if (tiktokUrl != null) socials.put("tiktok", tiktokUrl);
		return socials;
	}

	void setSocials(Map<String, String> socials) {
		this.instagramUrl = socials.get("instagram");
		this.facebookUrl = socials.get("facebook");
		this.xUrl = socials.get("x");
		this.tiktokUrl = socials.get("tiktok");
		touch();
	}

	void setLogoUrl(String logoUrl) {
		this.logoUrl = logoUrl;
		touch();
	}

	void setName(String name) {
		this.name = name;
		touch();
	}

	void setWebsite(String domain, String websiteUrl) {
		this.domain = domain;
		this.websiteUrl = websiteUrl;
		touch();
	}

	void setGa4PropertyId(String ga4PropertyId) {
		this.ga4PropertyId = ga4PropertyId;
		touch();
	}

	void setActive(boolean active) {
		this.active = active;
		touch();
	}

	private void touch() {
		this.updatedAt = Instant.now();
	}

	/** The logo's address on the brand's site, or null when there is none. */
	public String logoUrl() {
		return logoUrl;
	}

	/**
	 * True when the address ends in .png, .jpg, .jpeg, .gif or .webp (any case, the
	 * query string left out): a picture every mail app can show. ICO and SVG are not.
	 */
	public static boolean isPicture(String url) {
		if (url == null) return false;
		String path = url.replaceFirst("[?#].*$", "").toLowerCase(Locale.ROOT);
		return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif")
			|| path.endsWith(".webp");
	}

	public Long getId() { return id; }
	public String getName() { return name; }
	public String getDomain() { return domain; }
	public String getWebsiteUrl() { return websiteUrl; }
	public String getGa4PropertyId() { return ga4PropertyId; }
	public boolean isActive() { return active; }
	public Instant getCreatedAt() { return createdAt; }
}
