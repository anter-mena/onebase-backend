package com.onebase.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onebase.common.ApiException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Brands security layer and the readers behind "Add brand", without the
 * internet or a database: address checks, page reading, social links, logo addresses.
 */
class BrandSafetyTests {

	// ── UrlGuard: only public websites ─────────────────────────────────

	@Test
	void internalAddressesAreRefused() {
		for (String url : List.of(
				"http://localhost/", "http://localhost:8080/api/users", "http://127.0.0.1/", "http://[::1]/",
				"http://10.0.0.5/", "http://172.17.0.1/", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data",
				"http://100.64.0.1/", "http://0.0.0.0/", "http://[fd00::1]/", "http://[::ffff:10.0.0.1]/",
				"http://db/", "http://docker-socket-proxy:2375/containers/json", "http://onebase-db/",
				"http://printer.local/", "http://metadata.google.internal/")) {
			assertThatThrownBy(() -> UrlGuard.check(URI.create(url)))
				.as(url).isInstanceOf(UrlGuard.BlockedUrlException.class);
		}
	}

	@Test
	void onlyWebAddressesOnStandardPorts() {
		for (String url : List.of("ftp://example.com/", "file:///etc/passwd", "gopher://example.com/",
				"https://1.1.1.1:8080/", "https://user:pass@example.com/", "javascript:alert(1)")) {
			assertThatThrownBy(() -> UrlGuard.check(URI.create(url)))
				.as(url).isInstanceOf(UrlGuard.BlockedUrlException.class);
		}
	}

	@Test
	void aPublicAddressIsAllowed() {
		// An IP literal, so the test needs no DNS: 1.1.1.1 is public.
		assertThat(UrlGuard.check(URI.create("https://1.1.1.1/"))).isNotNull();
		assertThat(UrlGuard.check(URI.create("http://1.1.1.1:80/"))).isNotNull();
	}

	// ── BrandLinks: one brand per site, clean social links ─────────────

	@Test
	void theSameSiteIsTheSameBrand() {
		for (String typed : List.of("nike.com", "www.nike.com", "https://NIKE.com/en/", "http://www.nike.com/?q=1", "  nike.com  ")) {
			assertThat(BrandLinks.website(typed).domain()).as(typed).isEqualTo("nike.com");
		}
		assertThat(BrandLinks.website("https://www.nike.com/en").url()).isEqualTo("https://www.nike.com");
		assertThat(BrandLinks.website("nike.com").url()).isEqualTo("https://nike.com");
	}

	@Test
	void notAWebsiteIsRefusedWithASentence() {
		for (String typed : List.of("", "just words", "ftp://nike.com", "nike")) {
			assertThatThrownBy(() -> BrandLinks.website(typed)).as(typed).isInstanceOf(ApiException.class);
		}
	}

	@Test
	void socialLinksAreAccountsOnTheirOwnSite() {
		assertThat(BrandLinks.social("instagram", "instagram.com/nike/")).contains("https://www.instagram.com/nike");
		assertThat(BrandLinks.social("x", "https://twitter.com/Nike")).contains("https://x.com/Nike");
		assertThat(BrandLinks.social("facebook", "https://m.facebook.com/nike?ref=1")).contains("https://www.facebook.com/nike");
		assertThat(BrandLinks.social("tiktok", "https://www.tiktok.com/@nike")).contains("https://www.tiktok.com/@nike");

		assertThat(BrandLinks.social("tiktok", "https://www.tiktok.com/nike")).isEmpty();          // not an account
		assertThat(BrandLinks.social("facebook", "https://www.facebook.com/sharer/sharer.php")).isEmpty();
		assertThat(BrandLinks.social("x", "https://x.com/intent/tweet?text=hi")).isEmpty();
		assertThat(BrandLinks.social("instagram", "https://www.instagram.com/p/C123/")).isEmpty();
		assertThat(BrandLinks.social("instagram", "https://evil-instagram.com/nike")).isEmpty();   // look-alike
		assertThat(BrandLinks.social("instagram", "javascript:alert(1)")).isEmpty();

		assertThatThrownBy(() -> BrandLinks.socials(Map.of("instagram", "https://example.com/nike")))
			.isInstanceOf(ApiException.class).hasMessageContaining("Instagram");
	}

	// ── BrandLookup: reading a home page ───────────────────────────────

	@Test
	void aHomePageGivesTheNameTheAccountsAndTheIcons() {
		String html = """
			<html><head>
			  <title>Nike. Just Do It. Nike.com</title>
			  <meta property="og:site_name" content="Nike">
			  <link rel="icon" href="/favicon-16.png" sizes="16x16">
			  <link rel="icon" href="/favicon-32.png" sizes="32x32">
			  <link rel="icon" href="/logo.svg" type="image/svg+xml">
			  <link rel="apple-touch-icon" href="/apple-icon.png">
			  <script type="application/ld+json">{"sameAs":["https:\\/\\/www.tiktok.com\\/@nike"]}</script>
			</head><body>
			  <a href="https://www.facebook.com/sharer/sharer.php?u=x">Share</a>
			  <a href="https://www.facebook.com/nike">Facebook</a>
			  <a href="https://www.instagram.com/nike/">Instagram</a>
			  <a href="https://twitter.com/Nike">Twitter</a>
			</body></html>
			""";
		BrandLookup.Parsed parsed = BrandLookup.parse(html, URI.create("https://www.nike.com/"));
		assertThat(parsed.name()).isEqualTo("Nike");
		assertThat(parsed.socials()).containsEntry("facebook", "https://www.facebook.com/nike")
			.containsEntry("instagram", "https://www.instagram.com/nike")
			.containsEntry("x", "https://x.com/Nike")
			.containsEntry("tiktok", "https://www.tiktok.com/@nike");
		// The phone icon first, then the declared icons biggest first; the SVG last (emails can't show it).
		assertThat(parsed.icons()).extracting(URI::toString).containsExactly(
			"https://www.nike.com/apple-icon.png", "https://www.nike.com/favicon-32.png", "https://www.nike.com/favicon-16.png",
			"https://www.nike.com/logo.svg");
	}

	@Test
	void withoutASiteNameTheTitleIsUsed() {
		BrandLookup.Parsed parsed = BrandLookup.parse("<title>Home | Adidas</title>", URI.create("https://adidas.com/"));
		assertThat(parsed.name()).isEqualTo("Adidas");
		assertThat(BrandLookup.parse("<meta property=\"og:site_name\" content=\"Nike.com\">", URI.create("https://nike.com/")).name())
			.isEqualTo("Nike");
		assertThat(BrandLookup.nameFromDomain("new-balance.com")).isEqualTo("New Balance");
	}

	// ── Logos: only the address is kept, a PNG/JPEG first ────────────────

	@Test
	void theLogosEmailsCanShowAreTriedFirst() {
		String html = """
			<html><head>
			  <link rel="icon" href="/favicon.ico">
			  <link rel="icon" href="/logo.svg" type="image/svg+xml">
			  <meta property="og:image" content="https://cdn.nike.com/share.jpg?w=1200">
			  <link rel="icon" href="http://www.nike.com/icon-64.PNG?v=2" sizes="64x64">
			</head></html>
			""";
		BrandLookup.Parsed parsed = BrandLookup.parse(html, URI.create("https://www.nike.com/"));
		assertThat(BrandLookup.candidates(parsed.icons(), URI.create("https://www.nike.com/")))
			.extracting(URI::toString).containsExactly(
				"http://www.nike.com/icon-64.PNG?v=2", "https://cdn.nike.com/share.jpg?w=1200",
				"https://www.nike.com/apple-touch-icon.png",
				"https://www.nike.com/favicon.ico", "https://www.nike.com/logo.svg",
				"https://www.nike.com/favicon.ico");
	}

	@Test
	void onlyPngJpegGifAndWebpCountAsPicturesForEmails() {
		assertThat(Brand.isPicture("https://nike.com/a.png")).isTrue();
		assertThat(Brand.isPicture("https://nike.com/a.JPG?v=1")).isTrue();
		assertThat(Brand.isPicture("https://nike.com/a.jpeg#x")).isTrue();
		assertThat(Brand.isPicture("https://nike.com/a.gif")).isTrue();
		assertThat(Brand.isPicture("https://nike.com/a.webp")).isTrue();
		assertThat(Brand.isPicture("https://nike.com/favicon.ico")).isFalse();
		assertThat(Brand.isPicture("https://nike.com/logo.svg?as=.png")).isFalse();
		assertThat(Brand.isPicture("https://nike.com/icon")).isFalse();
		assertThat(Brand.isPicture(null)).isFalse();
	}
}
