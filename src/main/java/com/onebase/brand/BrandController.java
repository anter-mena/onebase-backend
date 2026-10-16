package com.onebase.brand;

import com.onebase.brand.BrandDtos.BrandResponse;
import com.onebase.brand.BrandDtos.LookupRequest;
import com.onebase.brand.BrandDtos.LookupResponse;
import com.onebase.brand.BrandDtos.SaveBrandRequest;
import com.onebase.brand.BrandDtos.StatusRequest;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration → Brands.
 *
 * <p>Who may do what (see `SecurityConfig`): the list is open to both roles —
 * Commercials will pick a brand for clients and payments — while adding,
 * editing, looking up a site and switching on/off are Admin-only. Logos are
 * not served from here: {@code logoUrl} is the logo's address on the brand's own
 * site, loaded from there.
 */
@RestController
@RequestMapping("/api/brands")
@Tag(name = "Brands", description = "The brands clients subscribe to. Reading: both roles. Changing: Admins only. No delete.")
public class BrandController {

	private final BrandService brandService;

	public BrandController(BrandService brandService) {
		this.brandService = brandService;
	}

	@GetMapping
	@Operation(summary = "Every brand, oldest first", description = "Both roles.")
	public List<BrandResponse> list() {
		return brandService.list();
	}

	@PostMapping("/lookup")
	@Operation(summary = "Read a website: name, social links and the logo's address",
		description = "Saves nothing. Public websites only — internal addresses are refused (400).")
	public LookupResponse lookUp(@Valid @RequestBody LookupRequest request) {
		return brandService.lookUp(request.url());
	}

	@PostMapping
	@Operation(summary = "Add a brand", description = "409 if another brand has the same website.")
	public ResponseEntity<BrandResponse> create(@AuthenticationPrincipal AuthPrincipal admin,
			@Valid @RequestBody SaveBrandRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(brandService.create(admin, request));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Edit a brand", description = "Name, website, social links and logo. Logged, saying what changed.")
	public BrandResponse update(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody SaveBrandRequest request) {
		return brandService.update(admin, id, request);
	}

	@PatchMapping("/{id}/status")
	@Operation(summary = "Switch a brand on or off", description = "Off: it can't be chosen for new clients or payments. Nothing is deleted.")
	public BrandResponse setStatus(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody StatusRequest request) {
		return brandService.setActive(admin, id, request.active());
	}
}
