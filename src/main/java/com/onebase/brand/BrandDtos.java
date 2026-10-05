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
			/** Our own address for the logo, or null when there is none. Changes when the logo does. */
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
			String logoUrl = brand.getLogo() == null ? null
				: "/api/brands/" + brand.getId() + "/logo?v=" + brand.getLogoUpdatedAt().toEpochMilli();
			return new BrandResponse(brand.getId(), brand.getName(), brand.getDomain(), brand.getWebsiteUrl(),
				brand.socials(), logoUrl, brand.isActive(), clients, brand.getGa4PropertyId(), brand.getCreatedAt());
		}
	}

	public record LookupRequest(@NotBlank(message = "Enter the brand's website, like nike.com.") String url) {
	}

	/**
	 * What the site gave, for the Admin to check before saving.
	 *
	 * @param logo          the logo we made, as a {@code data:image/png;base64,…} address, or null
	 * @param existingBrand the name of the brand already using this domain, when there is one
	 * @param warning       what could not be found, in a sentence; null when everything was
	 */
	public record LookupResponse(
			String name,
			String domain,
			String websiteUrl,
			Map<String, String> socials,
			String logo,
			String existingBrand,
			String warning) {
	}

	/**
	 * Add and Edit send the whole brand.
	 *
	 * @param logo           on Add: the logo from the lookup or an upload ({@code data:} address), or null for none.
	 *                       On Edit: a new one, or null to keep the current one
	 * @param removeLogo     Edit only: drop the logo (the initials are shown instead)
	 * @param fetchedFromSite the Admin pressed "Fetch again" before saving — said so in the Action log
	 * @param ga4PropertyId  the GA4 property number ("properties/123…" is accepted too); "" clears it,
	 *                       left out (null) keeps it as it is
	 */
	public record SaveBrandRequest(
			@NotBlank(message = "Enter the brand's website, like nike.com.") String websiteUrl,
			@NotBlank(message = "Enter the brand's name.") @Size(max = 100, message = "Keep the name under 100 characters.") String name,
			Map<String, String> socials,
			@Size(max = 1_500_000, message = "The logo is too large. Use a picture under 1 MB.") String logo,
			Boolean removeLogo,
			Boolean fetchedFromSite,
			@Size(max = 40, message = "The GA4 property ID is a number, like 412305881.") String ga4PropertyId) {

		/** Both flags are optional: left out means no. */
		public SaveBrandRequest {
			name = name == null ? null : name.trim();
			ga4PropertyId = ga4PropertyId == null ? null : ga4PropertyId.trim().replaceFirst("(?i)^properties/", "");
			removeLogo = Boolean.TRUE.equals(removeLogo);
			fetchedFromSite = Boolean.TRUE.equals(fetchedFromSite);
		}
	}

	public record StatusRequest(@NotNull(message = "Say whether the brand is active.") Boolean active) {
	}
}
