package com.onebase.brand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/** What crosses the wire for the Brands tab. */
public final class BrandDtos {

	private BrandDtos() {
	}

	/** One row of the Brands table. */
	public record BrandResponse(
			long id,
			String name,
			String domain,
			String websiteUrl,
			/** instagram / facebook / x / tiktok → link; missing networks are left out. */
			Map<String, String> socials,
			/** The logo's absolute address on the brand's own site, or null when there is none. */
			String logoUrl,
			boolean active,
			/** Clients on this brand, deleted ones left out. */
			long clients,
			/** The Google Analytics 4 property number, or null. */
			String ga4PropertyId,
			Instant createdAt) {

		static BrandResponse from(Brand brand) {
			return from(brand, 0);
		}

		static BrandResponse from(Brand brand, long clients) {
			return new BrandResponse(brand.getId(), brand.getName(), brand.getDomain(), brand.getWebsiteUrl(),
				brand.socials(), brand.logoUrl(), brand.isActive(), clients, brand.getGa4PropertyId(), brand.getCreatedAt());
		}
	}

	public record LookupRequest(@NotBlank(message = "Enter the brand's website, like nike.com.") String url) {
	}

	/**
	 * What the site gave, for the Admin to check before saving.
	 *
	 * @param logoUrl       the best logo on the site, as its absolute address, or null
	 * @param existingBrand the name of the brand already using this domain, when there is one
	 * @param warning       what could not be found, in a sentence; null when everything was
	 */
	public record LookupResponse(
			String name,
			String domain,
			String websiteUrl,
			Map<String, String> socials,
			String logoUrl,
			String existingBrand,
			String warning) {
	}

	/**
	 * Add and Edit send the whole brand.
	 *
	 * @param logoUrl        the logo's address on the brand's site (http or https); null or "" = none,
	 *                       and the initials are shown instead
	 * @param fetchedFromSite the Admin pressed "Fetch again" before saving — said so in the Action log
	 * @param ga4PropertyId  the GA4 property number ("properties/123…" is accepted too); "" clears it,
	 *                       left out (null) keeps it as it is
	 */
	public record SaveBrandRequest(
			@NotBlank(message = "Enter the brand's website, like nike.com.") String websiteUrl,
			@NotBlank(message = "Enter the brand's name.") @Size(max = 100, message = "Keep the name under 100 characters.") String name,
			Map<String, String> socials,
			@Size(max = 2000, message = "The logo address is too long. Keep it under 2000 characters.") String logoUrl,
			Boolean fetchedFromSite,
			@Size(max = 40, message = "The GA4 property ID is a number, like 412305881.") String ga4PropertyId) {

		/** The flag is optional: left out means no. */
		public SaveBrandRequest {
			name = name == null ? null : name.trim();
			logoUrl = logoUrl == null || logoUrl.isBlank() ? null : logoUrl.trim();
			ga4PropertyId = ga4PropertyId == null ? null : ga4PropertyId.trim().replaceFirst("(?i)^properties/", "");
			fetchedFromSite = Boolean.TRUE.equals(fetchedFromSite);
		}
	}

	public record StatusRequest(@NotNull(message = "Say whether the brand is active.") Boolean active) {
	}
}
