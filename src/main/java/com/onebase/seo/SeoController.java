package com.onebase.seo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SEO Overview. Admins only (not in {@code SecurityConfig.COMMERCIAL_API}). */
@RestController
@RequestMapping("/api/seo")
@Tag(name = "SEO", description = "Admins only: organic search per brand, live from Google Analytics 4 (kept 10 minutes).")
public class SeoController {

	private final SeoService service;

	public SeoController(SeoService service) {
		this.service = service;
	}

	@GetMapping("/brands")
	@Operation(summary = "Active brands with a GA4 property")
	public List<SeoService.SeoBrand> brands() {
		return service.brands();
	}

	@GetMapping("/overview")
	@Operation(summary = "Organic search for one brand",
		description = "range: today, yesterday, 7d, month (this month), year (this year) or custom with from/to (YYYY-MM-DD). Compared with the period of the same length just before.")
	public SeoService.Overview overview(@RequestParam long brandId, @RequestParam(defaultValue = "7d") String range,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
		return service.overview(brandId, range, from, to);
	}
}
