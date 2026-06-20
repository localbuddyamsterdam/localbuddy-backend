package com.localbuddy.payout;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.payment.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Transfer;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.TransferCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class StripeConnectPayoutProvider implements ConnectPayoutProvider {

    private final StripeProperties stripeProperties;
    private final StripeClient stripeClient;
    private final String refreshUrl;
    private final String returnUrl;

    public StripeConnectPayoutProvider(
            StripeProperties stripeProperties,
            StripeClient stripeClient,
            @Value("${app.payouts.connect.refresh-url:http://localhost:3000/host/payouts/onboarding/refresh}") String refreshUrl,
            @Value("${app.payouts.connect.return-url:http://localhost:3000/host/payouts/onboarding/return}") String returnUrl
    ) {
        this.stripeProperties = stripeProperties;
        this.stripeClient = stripeClient;
        this.refreshUrl = refreshUrl;
        this.returnUrl = returnUrl;
    }

    @Override
    public boolean isConfigured() {
        return stripeProperties.secretKey() != null && !stripeProperties.secretKey().trim().isEmpty();
    }

    @Override
    public String ensureConnectAccount(LocalProfile host) {
        requireConfigured();
        if (host.getStripeConnectAccountId() != null && !host.getStripeConnectAccountId().trim().isEmpty()) {
            return host.getStripeConnectAccountId();
        }
        try {
            AccountCreateParams.Builder params = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS);
            if (host.getUser() != null && host.getUser().getEmail() != null) {
                params.setEmail(host.getUser().getEmail());
            }
            Account account = stripeClient.accounts().create(params.build());
            return account.getId();
        } catch (Exception ex) {
            throw new BadRequestException("Unable to create Stripe Connect account: " + ex.getMessage());
        }
    }

    @Override
    public String createOnboardingLink(String connectAccountId) {
        requireConfigured();
        try {
            AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                    .setAccount(connectAccountId)
                    .setRefreshUrl(refreshUrl)
                    .setReturnUrl(returnUrl)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build();
            AccountLink link = stripeClient.accountLinks().create(params);
            return link.getUrl();
        } catch (Exception ex) {
            throw new BadRequestException("Unable to create Stripe onboarding link: " + ex.getMessage());
        }
    }

    @Override
    public String transfer(String connectAccountId, BigDecimal amount, String currency) {
        requireConfigured();
        try {
            long amountInCents = amount.movePointRight(2).longValueExact();
            TransferCreateParams params = TransferCreateParams.builder()
                    .setAmount(amountInCents)
                    .setCurrency(currency.toLowerCase())
                    .setDestination(connectAccountId)
                    .build();
            Transfer transfer = stripeClient.transfers().create(params);
            return transfer.getId();
        } catch (Exception ex) {
            throw new BadRequestException("Unable to transfer payout to host: " + ex.getMessage());
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BadRequestException("Stripe Connect is not configured");
        }
    }
}
