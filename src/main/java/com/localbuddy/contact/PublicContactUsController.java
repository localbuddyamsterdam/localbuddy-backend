package com.localbuddy.contact;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/contact-us")
@Tag(name = "Public - Contact Us", description = "Public/guest endpoint for submitting contact-us messages (rate limited)")
public class PublicContactUsController {

    private final ContactUsService contactUsService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicContactUsController(ContactUsService contactUsService,
                                     RateLimitService rateLimitService,
                                     ClientIpResolver clientIpResolver) {
        this.contactUsService = contactUsService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(
            summary = "Submit a contact-us message",
            description = "Submits a contact-us message from a visitor. Public/guest endpoint (rate limited per client IP)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact-us message submitted successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @PostMapping
    public ResponseEntity<ContactUsResponse> submitContactUs(
            HttpServletRequest servletRequest,
            @Valid @RequestBody ContactUsRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);

        rateLimitService.checkPublicApiLimit("contact-us:" + clientIp);

        ContactUsResponse response = contactUsService.submitContactUs(
                request,
                clientIp,
                servletRequest.getHeader("User-Agent")
        );

        return ResponseEntity.ok(response);
    }
}