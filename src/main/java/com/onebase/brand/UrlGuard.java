package com.onebase.brand;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides whether the server may open an address an Admin typed.
 *
 * <p><b>Why this exists.</b> "Add brand" makes our server download a page from
 * an address someone types. Without a check, that someone could type an address
 * that only the server can reach — the database, the Docker helper, the backend
 * itself on {@code localhost:8080}, a cloud metadata service — and use our
 * server to look inside our own network (known as SSRF). So every address is
 * checked here, and again at every redirect (see {@link SafeFetcher}).
 *
 * <p>Allowed: {@code http} / {@code https}, ports 80 and 443 only, a real host
 * name with a dot in it (so Docker's own names like {@code db} are out), and
 * only if <b>every</b> address the name resolves to is on the public internet.
 *
 * <p>⚠️ Known limit: the name is resolved here and again when connecting, so a
 * hostile DNS server could answer differently the second time ("DNS
 * rebinding"). Only Admins can trigger a fetch, the ports are limited to 80/443
 * and nothing we run listens on those inside the network, which keeps the
 * damage small. Closing it fully would mean connecting to the checked IP
 * directly — noted in BugForLater.md.
 */
final class UrlGuard {

	private UrlGuard() {
	}

	/** Thrown with a sentence the Admin can read. */
	static final class BlockedUrlException extends RuntimeException {
		BlockedUrlException(String message) {
			super(message);
		}
	}

	/** The address, cleaned up, if it may be opened; otherwise {@link BlockedUrlException}. */
	static URI check(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new BlockedUrlException("Only website addresses (http or https) can be used.");
		}
		if (uri.getRawUserInfo() != null) {
			throw new BlockedUrlException("Remove the user name or password from the address.");
		}
		int port = uri.getPort();
		if (port != -1 && port != 80 && port != 443) {
			throw new BlockedUrlException("Only standard website addresses can be used (no port number).");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new BlockedUrlException("This is not a website address.");
		}
		host = asciiHost(host);
		if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")
				|| host.equals("localhost")) {
			throw new BlockedUrlException("This address can't be used: it is not a public website.");
		}

		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new BlockedUrlException("This website doesn't exist, or can't be found right now.");
		}
		for (InetAddress address : addresses) {
			if (!isPublic(address)) {
				throw new BlockedUrlException("This address can't be used: it is not a public website.");
			}
		}
		return uri;
	}

	/** "Bücher.de" → "xn--bcher-kva.de", lowercase, no trailing dot. */
	static String asciiHost(String host) {
		String trimmed = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
		try {
			return IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			throw new BlockedUrlException("This is not a website address.");
		}
	}

	/** True only for addresses on the public internet. */
	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] b = address.getAddress();
		if (address instanceof Inet4Address) {
			return isPublicV4(b);
		}
		if (address instanceof Inet6Address) {
			// IPv4 written as IPv6 (::ffff:10.0.0.1, 64:ff9b::/96): judge the IPv4 inside.
			boolean mapped = true;
			for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
			if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
				return isPublicV4(new byte[] { b[12], b[13], b[14], b[15] });
			}
			if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
				return isPublicV4(new byte[] { b[12], b[13], b[14], b[15] });
			}
			if ((b[0] & 0xfe) == 0xfc) return false; // fc00::/7, private (unique local)
			if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
				return false; // 2001:db8::/32, documentation only
			}
			return true;
		}
		return false;
	}

	private static boolean isPublicV4(byte[] b) {
		int a = b[0] & 0xff;
		int c = b[1] & 0xff;
		if (a == 0 || a == 10 || a == 127) return false;              // "this network", private, loopback
		if (a == 100 && c >= 64 && c <= 127) return false;            // 100.64/10, carrier-grade NAT
		if (a == 169 && c == 254) return false;                       // link-local, cloud metadata
		if (a == 172 && c >= 16 && c <= 31) return false;             // private
		if (a == 192 && c == 168) return false;                       // private
		if (a == 192 && c == 0 && (b[2] & 0xff) == 0) return false;   // 192.0.0/24, special
		if (a == 198 && (c == 18 || c == 19)) return false;           // benchmarking
		if (a >= 224) return false;                                   // multicast and reserved
		return true;
	}
}
