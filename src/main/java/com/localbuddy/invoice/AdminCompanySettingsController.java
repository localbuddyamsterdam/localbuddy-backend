package com.localbuddy.invoice;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/company-settings")
@Tag(name = "Admin - Company settings", description = "Issuer legal/BTW details used on invoices")
public class AdminCompanySettingsController {

    private final CompanySettingsService companySettingsService;

    public AdminCompanySettingsController(CompanySettingsService companySettingsService) {
        this.companySettingsService = companySettingsService;
    }

    @GetMapping
    public ResponseEntity<CompanySettingsResponse> get() {
        return ResponseEntity.ok(companySettingsService.get());
    }

    @PutMapping
    public ResponseEntity<CompanySettingsResponse> upsert(@Valid @RequestBody UpsertCompanySettingsRequest request) {
        return ResponseEntity.ok(companySettingsService.upsert(request));
    }
}
