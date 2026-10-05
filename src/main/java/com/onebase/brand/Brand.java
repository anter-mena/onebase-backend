package com.onebase.brand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A brand (table {@code brands}). There is no delete: a brand is switched off,
 * so clients and payments keep pointing at it.
 *
 * <p>The logo is stored here, as the small PNG {@link LogoImages} made — never
 * as the file that was found or uploaded.
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

	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(name = "logo")
	private byte[] logo;

	@Column(name = "logo_updated_at")
	private Instant logoUpdatedAt;

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

	void setLogo(byte[] png) {
		this.logo = png;
		this.logoUpdatedAt = png == null ? null : Instant.now();
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

	public Long getId() { return id; }
	public String getName() { return name; }
	public String getDomain() { return domain; }
	public String getWebsiteUrl() { return websiteUrl; }
	public byte[] getLogo() { return logo; }
	public Instant getLogoUpdatedAt() { return logoUpdatedAt; }
	public String getGa4PropertyId() { return ga4PropertyId; }
	public boolean isActive() { return active; }
	public Instant getCreatedAt() { return createdAt; }
}
