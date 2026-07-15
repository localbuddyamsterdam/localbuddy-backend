package com.localbuddy.incident;

import com.localbuddy.notification.email.EmailProviderService;
import com.localbuddy.notification.email.EmailSendRequest;
import com.localbuddy.notification.email.EmailSendResult;
import com.localbuddy.notification.email.EmailTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sends incident alert emails to operators. This is the part that <em>must not</em> depend on the
 * database: recipients come from config (not a users query), the email body is templated in memory,
 * and delivery goes straight through {@link EmailProviderService} rather than the DB-backed
 * notification queue (whose rows and processor are unreachable during a DB outage).
 */
@Service
public class IncidentAlertService {

    private static final Logger log = LoggerFactory.getLogger(IncidentAlertService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private enum Kind { OPENED, RESOLVED, REMINDER, TEST }

    private final EmailProviderService emailProvider;
    private final EmailTemplateService emailTemplates;
    private final IncidentProperties properties;
    private final String supportEmail;
    private final String statusPageUrl;

    public IncidentAlertService(EmailProviderService emailProvider,
                                EmailTemplateService emailTemplates,
                                IncidentProperties properties,
                                @Value("${app.support.email:}") String supportEmail,
                                @Value("${app.frontend.base-url:}") String frontendBaseUrl) {
        this.emailProvider = emailProvider;
        this.emailTemplates = emailTemplates;
        this.properties = properties;
        this.supportEmail = supportEmail;
        this.statusPageUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
    }

    public void sendIncidentOpened(Incident incident, Instant now) {
        send(Kind.OPENED, incident, now);
    }

    public void sendIncidentResolved(Incident incident, Instant now) {
        send(Kind.RESOLVED, incident, now);
    }

    public void sendIncidentReminder(Incident incident, Instant now) {
        if (incident != null) {
            send(Kind.REMINDER, incident, now);
        }
    }

    /**
     * Fire a synthetic alert so admins can confirm the whole path (provider config, sender identity,
     * deliverability) is wired up <em>before</em> a real 3am outage. Returns the resolved recipients.
     */
    public List<String> sendTestAlert(Instant now) {
        Incident sample = new Incident("inc_test01", "database",
                "SQLTransientConnectionException: connection is not available (sample)", now, null);
        send(Kind.TEST, sample, now);
        return recipients();
    }

    /** Config recipients, or the support inbox as a last resort — de-duplicated, order preserved. */
    public List<String> recipients() {
        Set<String> out = new LinkedHashSet<>();
        for (String email : properties.alertEmails()) {
            if (email != null && !email.isBlank()) {
                out.add(email.trim());
            }
        }
        if (out.isEmpty() && supportEmail != null && !supportEmail.isBlank()) {
            out.add(supportEmail.trim());
        }
        return new ArrayList<>(out);
    }

    private void send(Kind kind, Incident incident, Instant now) {
        List<String> recipients = recipients();
        if (recipients.isEmpty()) {
            log.warn("Incident alert ({}) not sent: no recipients configured (set app.incident.alert-emails "
                    + "or app.support.email)", kind);
            return;
        }

        String subject = subject(kind, incident, now);
        String plain = plainBody(kind, incident, now);
        String html = emailTemplates.renderIncidentAlert(model(kind, incident, now));

        int sent = 0;
        for (String to : recipients) {
            try {
                EmailSendResult result = emailProvider.sendEmail(new EmailSendRequest(to, subject, plain, html, null));
                if (result.success()) {
                    sent++;
                } else {
                    log.warn("Incident alert ({}) to {} failed: {}", kind, to, result.failureReason());
                }
            } catch (Exception ex) {
                // Never let a delivery failure kill the monitor loop.
                log.warn("Incident alert ({}) to {} threw: {}", kind, to, ex.getMessage());
            }
        }
        log.info("Incident alert ({}) dispatched to {}/{} recipient(s) [incident={}]",
                kind, sent, recipients.size(), incident == null ? "-" : incident.id());
    }

    private String subject(Kind kind, Incident incident, Instant now) {
        String component = incident == null ? "system" : incident.component();
        return switch (kind) {
            case OPENED -> "🔴 LocalBuddy incident — " + component + " unreachable";
            case REMINDER -> "🔴 Still down — LocalBuddy " + component + " unreachable ("
                    + humanDuration(incident.duration(now)) + ")";
            case RESOLVED -> "✅ LocalBuddy recovered — " + component + " reachable again";
            case TEST -> "🧪 LocalBuddy incident alert — test message";
        };
    }

    private String plainBody(Kind kind, Incident incident, Instant now) {
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case OPENED, REMINDER -> sb.append("LocalBuddy has detected a service incident.\n\n");
            case RESOLVED -> sb.append("A LocalBuddy service incident has been resolved.\n\n");
            case TEST -> sb.append("This is a TEST of the LocalBuddy incident alerting path. "
                    + "No real incident is in progress.\n\n");
        }
        if (incident != null) {
            sb.append("Incident:  ").append(incident.id()).append('\n');
            sb.append("Component: ").append(incident.component()).append('\n');
            sb.append("Started:   ").append(TS.format(incident.startedAt())).append('\n');
            if (kind == Kind.RESOLVED) {
                sb.append("Resolved:  ").append(TS.format(now)).append('\n');
            }
            sb.append("Duration:  ").append(humanDuration(incident.duration(now))).append('\n');
            sb.append("Detail:    ").append(incident.reason()).append('\n');
        }
        if (!statusPageUrl.isBlank()) {
            sb.append("\nApp: ").append(statusPageUrl).append('\n');
        }
        return sb.toString();
    }

    private EmailTemplateService.IncidentAlertModel model(Kind kind, Incident incident, Instant now) {
        String accent = switch (kind) {
            case OPENED, REMINDER -> "#d62f2a"; // red
            case RESOLVED -> "#1a8f4c";         // green
            case TEST -> "#b7791f";             // amber
        };
        String statusLabel = switch (kind) {
            case OPENED -> "Incident detected";
            case REMINDER -> "Still ongoing";
            case RESOLVED -> "Resolved";
            case TEST -> "Test alert";
        };
        String component = incident == null ? "system" : incident.component();
        String headline = switch (kind) {
            case OPENED, REMINDER -> capitalize(component) + " is unreachable";
            case RESOLVED -> capitalize(component) + " is reachable again";
            case TEST -> "Alerting path is working";
        };
        String intro = switch (kind) {
            case OPENED -> "Automated monitoring could not reach the " + component
                    + ". Guests may be seeing errors on data-backed pages while this persists.";
            case REMINDER -> "The " + component + " is still unreachable. This is a reminder that the "
                    + "incident opened earlier is ongoing.";
            case RESOLVED -> "Automated monitoring can reach the " + component
                    + " again and the public status banner has been cleared.";
            case TEST -> "This is a test message triggered from the admin console. It confirms the "
                    + "incident email path is configured and deliverable.";
        };

        List<String[]> rows = new ArrayList<>();
        if (incident != null) {
            rows.add(new String[]{"Incident", incident.id()});
            rows.add(new String[]{"Component", incident.component()});
            rows.add(new String[]{"Started", TS.format(incident.startedAt())});
            if (kind == Kind.RESOLVED) {
                rows.add(new String[]{"Resolved", TS.format(now)});
            }
            rows.add(new String[]{"Duration", humanDuration(incident.duration(now))});
            rows.add(new String[]{"Detail", incident.reason()});
        }

        String note = kind == Kind.TEST
                ? "You are receiving this because you are on app.incident.alert-emails (or app.support.email)."
                : "Automated alert from LocalBuddy monitoring. You are receiving this as a configured operator.";

        return new EmailTemplateService.IncidentAlertModel(accent, statusLabel, headline, intro, rows, note);
    }

    private static String humanDuration(Duration d) {
        long seconds = Math.max(0, d.getSeconds());
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "System";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
