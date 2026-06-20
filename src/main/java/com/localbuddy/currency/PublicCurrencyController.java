package com.localbuddy.currency;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Public, unauthenticated currency endpoints under {@code /api/public/currency}
 * (covered by the {@code /api/public/**} permitAll rule).
 */
@RestController
@RequestMapping("/api/public/currency")
public class PublicCurrencyController {

    private final CurrencyConversionService currencyConversionService;

    public PublicCurrencyController(CurrencyConversionService currencyConversionService) {
        this.currencyConversionService = currencyConversionService;
    }

    @GetMapping("/convert")
    public ResponseEntity<ConversionResponse> convert(
            @RequestParam BigDecimal amount,
            @RequestParam String from,
            @RequestParam String to) {
        BigDecimal converted = currencyConversionService.convert(amount, from, to);
        BigDecimal rate = currencyConversionService.rate(from, to);
        return ResponseEntity.ok(new ConversionResponse(
                from.trim().toUpperCase(),
                to.trim().toUpperCase(),
                amount,
                converted,
                rate));
    }

    @GetMapping("/rates")
    public ResponseEntity<RatesResponse> rates(@RequestParam(defaultValue = "EUR") String base) {
        Map<String, BigDecimal> rates = currencyConversionService.getRates(base);
        return ResponseEntity.ok(new RatesResponse(base.trim().toUpperCase(), rates));
    }

    public record ConversionResponse(
            String from,
            String to,
            BigDecimal amount,
            BigDecimal convertedAmount,
            BigDecimal rate) {
    }

    public record RatesResponse(String base, Map<String, BigDecimal> rates) {
    }
}
