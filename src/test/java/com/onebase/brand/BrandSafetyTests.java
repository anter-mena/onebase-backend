package com.onebase.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onebase.common.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The Brands security layer and the readers behind "Add brand", without the
 * internet or a database: address checks, page reading, social links, logos.
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
		// The phone icon first, then the declared icons biggest first; the SVG is left out.
		assertThat(parsed.icons()).extracting(URI::toString).containsExactly(
			"https://www.nike.com/apple-icon.png", "https://www.nike.com/favicon-32.png", "https://www.nike.com/favicon-16.png");
	}

	@Test
	void withoutASiteNameTheTitleIsUsed() {
		BrandLookup.Parsed parsed = BrandLookup.parse("<title>Home | Adidas</title>", URI.create("https://adidas.com/"));
		assertThat(parsed.name()).isEqualTo("Adidas");
		assertThat(BrandLookup.parse("<meta property=\"og:site_name\" content=\"Nike.com\">", URI.create("https://nike.com/")).name())
			.isEqualTo("Nike");
		assertThat(BrandLookup.nameFromDomain("new-balance.com")).isEqualTo("New Balance");
	}

	// ── LogoImages: we only keep a small PNG we made ───────────────────

	@Test
	void aBigLogoIsShrunkAndSavedAsANewPng() throws Exception {
		byte[] jpeg = picture(600, 300, "jpeg");
		byte[] stored = LogoImages.toStoredPng(jpeg).orElseThrow();
		BufferedImage result = ImageIO.read(new ByteArrayInputStream(stored));
		assertThat(result.getWidth()).isEqualTo(LogoImages.SIZE);
		assertThat(result.getHeight()).isEqualTo(64);
		assertThat(stored[1]).isEqualTo((byte) 'P'); // a PNG, whatever came in
	}

	@Test
	void aFaviconFileIsRead() throws Exception {
		byte[] png = picture(48, 48, "png");
		ByteBuffer ico = ByteBuffer.allocate(6 + 16 + png.length).order(ByteOrder.LITTLE_ENDIAN);
		ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
		ico.put((byte) 48).put((byte) 48).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
			.putInt(png.length).putInt(22);
		ico.put(png);
		assertThat(LogoImages.toStoredPng(ico.array())).isPresent();
	}

	@Test
	void whatIsNotAPictureIsRefused() {
		assertThat(LogoImages.toStoredPng("<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8))).isEmpty();
		assertThat(LogoImages.toStoredPng("<html>not an image</html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
		assertThat(LogoImages.toStoredPng(new byte[3])).isEmpty();
	}

	@Test
	void aPictureClaimingToBeHugeIsRefusedBeforeDecoding() throws Exception {
		// A real PNG header saying 20000 × 20000 pixels: refused from the header alone.
		byte[] png = picture(10, 10, "png");
		ByteBuffer.wrap(png).putInt(16, 20_000).putInt(20, 20_000);
		assertThat(LogoImages.toStoredPng(png)).isEmpty();
	}

	private static byte[] picture(int width, int height, String format) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}
}
