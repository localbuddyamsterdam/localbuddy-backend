package com.localbuddy.notification.email;

import org.springframework.stereotype.Service;

/**
 * Renders branded, email-safe HTML for transactional emails. Uses tables + inline CSS
 * (Outlook/Gmail-safe) and the LocalBuddy brand tokens. All caller-supplied text is HTML-escaped.
 * The plain-text version of each email is built by the caller and carried separately as the fallback.
 */
@Service
public class EmailTemplateService {

    public record BookingConfirmationModel(
            String greetingName,
            String experienceTitle,
            String hostLine,
            String whenDate,
            String whenTime,
            String guestsMain,
            String guestsSub,
            String meetingMain,
            String meetingSub,
            String totalText,
            String totalSub,
            String bookingReference,
            String manageUrl,
            String calendarUrl,
            String heroImageUrl
    ) {
    }

    public String renderBookingConfirmation(BookingConfirmationModel m) {
        String hero;
        if (!blank(m.heroImageUrl())) {
            hero = "<tr><td style=\"padding:0;line-height:0;font-size:0;\">"
                    + "<img src=\"" + esc(m.heroImageUrl()) + "\" width=\"600\" alt=\"" + esc(m.experienceTitle()) + "\" "
                    + "style=\"display:block;width:100%;max-width:600px;height:240px;object-fit:cover;border:0;\" />"
                    + "</td></tr>";
        } else {
            hero = "<tr><td style=\"background:#111114;padding:46px 32px;text-align:center;\">"
                    + "<div style=\"font-size:12px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#ffde5d;\">LocalBuddy experience</div>"
                    + "<div style=\"margin-top:8px;font-size:22px;font-weight:600;color:#ffffff;line-height:1.25;\">" + esc(m.experienceTitle()) + "</div>"
                    + "</td></tr>";
        }

        String calendarButton = blank(m.calendarUrl()) ? "" :
                "<td class=\"lb-cta-cell\"><a class=\"lb-btn-ghost\" href=\"" + esc(m.calendarUrl()) + "\" "
                        + "style=\"display:inline-block;background:#ffffff;color:#111114;font-size:15px;font-weight:600;line-height:1;padding:13px 24px;border-radius:999px;border:1px solid #d6d6db;\">Add to calendar</a></td>";

        return TEMPLATE
                .replace("{{hero}}", hero)
                .replace("{{greeting}}", esc(m.greetingName()))
                .replace("{{title}}", esc(m.experienceTitle()))
                .replace("{{hostLine}}", esc(m.hostLine()))
                .replace("{{whenDate}}", esc(m.whenDate()))
                .replace("{{whenTime}}", esc(m.whenTime()))
                .replace("{{guestsMain}}", esc(m.guestsMain()))
                .replace("{{guestsSub}}", sub(m.guestsSub()))
                .replace("{{meetingMain}}", esc(m.meetingMain()))
                .replace("{{meetingSub}}", sub(m.meetingSub()))
                .replace("{{totalText}}", esc(m.totalText()))
                .replace("{{totalSub}}", sub(m.totalSub()))
                .replace("{{ref}}", esc(m.bookingReference()))
                .replace("{{manageUrl}}", esc(m.manageUrl()))
                .replace("{{calendarButton}}", calendarButton);
    }

    /** A muted secondary line under a detail value, or nothing when blank. */
    private static String sub(String value) {
        return blank(value) ? "" : "<div style=\"font-size:14px;color:#6e6e73;\">" + esc(value) + "</div>";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <meta name="color-scheme" content="light only" />
            <title>Your booking is confirmed</title>
            <style>
              body { margin:0; padding:0; background:#e9eaed; }
              a { text-decoration:none; }
              .lb-body { font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; -webkit-font-smoothing:antialiased; }
              .lb-btn-primary:hover { background:#c0241f !important; }
              .lb-btn-ghost:hover { border-color:#111114 !important; }
              .lb-flink:hover { color:#ffffff !important; }
              @media (max-width:620px) {
                .lb-container { width:100% !important; border-radius:0 !important; }
                .lb-pad { padding-left:22px !important; padding-right:22px !important; }
                .lb-stack { display:block !important; width:100% !important; padding:0 0 18px 0 !important; }
                .lb-cta { width:100% !important; }
                .lb-cta-cell { display:block !important; width:100% !important; padding:0 0 12px 0 !important; }
                .lb-cta-cell a { display:block !important; text-align:center !important; }
                .lb-ref-label, .lb-ref-value { display:block !important; width:100% !important; text-align:left !important; }
                .lb-ref-value { padding-top:10px !important; }
              }
            </style>
            </head>
            <body>
            <div class="lb-body" style="background:#e9eaed; padding:26px 12px;">
              <div style="display:none; max-height:0; overflow:hidden; opacity:0; color:#e9eaed; font-size:1px; line-height:1px;">You're all set — your booking is confirmed. Reference {{ref}}.</div>
              <table role="presentation" class="lb-container" width="600" align="center" cellpadding="0" cellspacing="0" style="width:600px; max-width:600px; margin:0 auto; background:#ffffff; border-radius:18px; overflow:hidden; box-shadow:0 14px 40px rgba(12,19,32,0.14);">
                <tr>
                  <td style="background:#ffde5d; padding:18px 32px; text-align:center;">
                    <span style="font-size:20px; font-weight:600; letter-spacing:-0.01em; color:#111114;">
                      <span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:#d62f2a; vertical-align:middle; margin-right:8px;"></span>LocalBuddy
                    </span>
                  </td>
                </tr>
                {{hero}}
                <tr>
                  <td class="lb-pad" style="padding:32px;">
                    <span style="display:inline-block; background:#e7f5ec; color:#1a8f4c; font-size:12px; font-weight:600; letter-spacing:0.04em; text-transform:uppercase; padding:5px 12px; border-radius:999px;">&#10003;&nbsp; Booking confirmed</span>
                    <h1 style="margin:16px 0 10px; font-size:28px; line-height:1.2; font-weight:600; letter-spacing:-0.01em; color:#111114;">You're all set, {{greeting}}!</h1>
                    <p style="margin:0 0 24px; font-size:16px; line-height:1.55; color:#6e6e73;">Your spot on the <strong style="color:#111114; font-weight:600;">{{title}}</strong> is booked. Here's everything you need for the day.</p>
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#ffffff; border:1px solid #e6e6e9; border-radius:16px;">
                      <tr>
                        <td style="padding:20px 22px 0;">
                          <div style="font-size:18px; font-weight:600; color:#111114;">{{title}}</div>
                          <div style="font-size:14px; color:#6e6e73; margin-top:3px;">{{hostLine}}</div>
                          <div style="height:1px; background:#e6e6e9; margin:16px 0 0;"></div>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:18px 22px 2px;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td class="lb-stack" width="50%" style="vertical-align:top; padding:0 12px 18px 0;">
                                <div style="font-size:11px; font-weight:600; letter-spacing:0.05em; text-transform:uppercase; color:#86868b; margin-bottom:5px;">When</div>
                                <div style="font-size:16px; font-weight:600; color:#111114;">{{whenDate}}</div>
                                <div style="font-size:14px; color:#6e6e73;">{{whenTime}}</div>
                              </td>
                              <td class="lb-stack" width="50%" style="vertical-align:top; padding:0 0 18px 0;">
                                <div style="font-size:11px; font-weight:600; letter-spacing:0.05em; text-transform:uppercase; color:#86868b; margin-bottom:5px;">Guests</div>
                                <div style="font-size:16px; font-weight:600; color:#111114;">{{guestsMain}}</div>
                                {{guestsSub}}
                              </td>
                            </tr>
                            <tr>
                              <td class="lb-stack" width="50%" style="vertical-align:top; padding:0 12px 20px 0;">
                                <div style="font-size:11px; font-weight:600; letter-spacing:0.05em; text-transform:uppercase; color:#86868b; margin-bottom:5px;">Meeting point</div>
                                <div style="font-size:16px; font-weight:600; color:#111114;">{{meetingMain}}</div>
                                {{meetingSub}}
                              </td>
                              <td class="lb-stack" width="50%" style="vertical-align:top; padding:0 0 20px 0;">
                                <div style="font-size:11px; font-weight:600; letter-spacing:0.05em; text-transform:uppercase; color:#86868b; margin-bottom:5px;">Total paid</div>
                                <div style="font-size:16px; font-weight:600; color:#111114;">{{totalText}}</div>
                                {{totalSub}}
                              </td>
                            </tr>
                          </table>
                          <div style="height:1px; background:#e6e6e9;"></div>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:16px 22px 20px;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td class="lb-ref-label" style="vertical-align:middle; font-size:11px; font-weight:600; letter-spacing:0.05em; text-transform:uppercase; color:#86868b;">Booking reference</td>
                              <td class="lb-ref-value" style="vertical-align:middle; text-align:right;">
                                <span style="display:inline-block; white-space:nowrap; font-family:'SFMono-Regular',ui-monospace,Consolas,monospace; font-size:14px; font-weight:600; color:#111114; background:#f5f5f7; border:1px solid #e6e6e9; border-radius:8px; padding:5px 11px;">{{ref}}</span>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                    <table role="presentation" class="lb-cta" cellpadding="0" cellspacing="0" style="margin:24px 0 4px;">
                      <tr>
                        <td class="lb-cta-cell" style="padding-right:10px;">
                          <a class="lb-btn-primary" href="{{manageUrl}}" style="display:inline-block; background:#d62f2a; color:#ffffff; font-size:15px; font-weight:600; line-height:1; padding:14px 26px; border-radius:999px;">Manage your booking</a>
                        </td>
                        {{calendarButton}}
                      </tr>
                    </table>
                    <p style="margin:18px 0 0; font-size:14px; line-height:1.55; color:#86868b;">Your host will message you before the day with the final meet-up details. Plans changed? You can view or cancel your booking anytime.</p>
                  </td>
                </tr>
                <tr>
                  <td style="background:#111114; padding:36px 32px 30px; text-align:center;">
                    <span style="font-size:20px; font-weight:600; letter-spacing:-0.01em; color:#ffffff;">
                      <span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:#d62f2a; vertical-align:middle; margin-right:8px;"></span>LocalBuddy
                    </span>
                    <p style="margin:12px auto 0; max-width:340px; font-size:13px; line-height:1.6; color:rgba(255,255,255,0.55);">Real experiences with the locals who actually live here. Amsterdam born, Europe bound.</p>
                    <p style="margin:20px 0 18px; font-size:13px; color:rgba(255,255,255,0.72);">
                      <a class="lb-flink" href="{{manageUrl}}" style="color:rgba(255,255,255,0.72);">Manage booking</a>
                      <span style="color:rgba(255,255,255,0.28);">&nbsp;&middot;&nbsp;</span>
                      <a class="lb-flink" href="mailto:support@localbuddy.com" style="color:rgba(255,255,255,0.72);">Contact support</a>
                    </p>
                    <div style="height:1px; background:rgba(255,255,255,0.12); margin:0 0 16px;"></div>
                    <p style="margin:0; font-size:12px; color:rgba(255,255,255,0.42);">&copy; LocalBuddy B.V. &nbsp;&middot;&nbsp; Amsterdam, The Netherlands</p>
                  </td>
                </tr>
              </table>
            </div>
            </body>
            </html>
            """;

    /**
     * Wraps a plain-text notification (subject + message) in the branded LocalBuddy shell:
     * yellow header, a headline, the message as escaped paragraphs with any URLs turned into
     * links, and the navy footer. Used to auto-brand every email that has no bespoke template.
     */
    public String renderGeneric(String subject, String message) {
        String headline = esc(subject == null ? "LocalBuddy" : subject);
        String content = "<h1 style=\"margin:0 0 16px; font-size:26px; line-height:1.25; font-weight:600; letter-spacing:-0.01em; color:#111114;\">"
                + headline + "</h1>"
                + "<div style=\"font-size:16px; line-height:1.6; color:#3a3a3f;\">" + formatMessage(message) + "</div>";
        String preheader = message == null ? "" : message.replace("\n", " ").trim();
        if (preheader.length() > 140) {
            preheader = preheader.substring(0, 140);
        }
        return GENERIC_SHELL
                .replace("{{title}}", headline)
                .replace("{{preheader}}", esc(preheader))
                .replace("{{content}}", content);
    }

    /** Escapes text and turns bare http(s) URLs into styled links; newlines become &lt;br&gt;. */
    private String formatMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String[] lines = message.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String[] tokens = lines[i].split(" ");
            for (int j = 0; j < tokens.length; j++) {
                String t = tokens[j];
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    sb.append("<a href=\"").append(esc(t))
                            .append("\" style=\"color:#d62f2a; font-weight:600; word-break:break-all;\">")
                            .append(esc(t)).append("</a>");
                } else {
                    sb.append(esc(t));
                }
                if (j < tokens.length - 1) {
                    sb.append(" ");
                }
            }
            if (i < lines.length - 1) {
                sb.append("<br>");
            }
        }
        return sb.toString();
    }

    /**
     * Model for an incident/status alert email sent to operators. Fields are plain strings so this
     * template stays decoupled from the incident feature's domain types.
     *
     * @param accentHex   status colour for the pill (e.g. red for down, green for resolved)
     * @param statusLabel short pill text, e.g. "Incident detected"
     * @param headline    one-line summary
     * @param intro       a sentence or two of context
     * @param rows        {label, value} detail pairs rendered as a table (may be empty)
     * @param note        muted footer line (why you're getting this)
     */
    public record IncidentAlertModel(
            String accentHex,
            String statusLabel,
            String headline,
            String intro,
            java.util.List<String[]> rows,
            String note
    ) {
    }

    /** Renders an operator incident alert in the branded shell. All caller text is HTML-escaped. */
    public String renderIncidentAlert(IncidentAlertModel m) {
        StringBuilder rowsHtml = new StringBuilder();
        for (String[] row : m.rows()) {
            rowsHtml.append("<tr>")
                    .append("<td style=\"padding:9px 0;font-size:11px;font-weight:600;letter-spacing:0.04em;")
                    .append("text-transform:uppercase;color:#86868b;white-space:nowrap;vertical-align:top;width:96px;\">")
                    .append(esc(row[0])).append("</td>")
                    .append("<td style=\"padding:9px 0 9px 16px;font-size:14px;line-height:1.5;color:#111114;")
                    .append("vertical-align:top;word-break:break-word;\">")
                    .append(esc(row[1])).append("</td>")
                    .append("</tr>");
        }
        String table = m.rows().isEmpty() ? "" :
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"margin:20px 0 4px;border-top:1px solid #e6e6e9;\">" + rowsHtml + "</table>";

        String pill = "<span style=\"display:inline-block;background:" + m.accentHex()
                + ";color:#ffffff;font-size:12px;font-weight:600;letter-spacing:0.04em;text-transform:uppercase;"
                + "padding:5px 12px;border-radius:999px;\">" + esc(m.statusLabel()) + "</span>";

        String content = pill
                + "<h1 style=\"margin:16px 0 10px;font-size:26px;line-height:1.25;font-weight:600;"
                + "letter-spacing:-0.01em;color:#111114;\">" + esc(m.headline()) + "</h1>"
                + "<p style=\"margin:0;font-size:16px;line-height:1.6;color:#3a3a3f;\">" + esc(m.intro()) + "</p>"
                + table
                + "<p style=\"margin:22px 0 0;font-size:13px;line-height:1.55;color:#86868b;\">" + esc(m.note()) + "</p>";

        String preheader = m.headline() == null ? "" : m.headline();
        return GENERIC_SHELL
                .replace("{{title}}", esc(m.headline()))
                .replace("{{preheader}}", esc(preheader))
                .replace("{{content}}", content);
    }

    private static final String GENERIC_SHELL = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <meta name="color-scheme" content="light only" />
            <title>{{title}}</title>
            <style>
              body { margin:0; padding:0; background:#e9eaed; }
              a { text-decoration:none; }
              .lb-body { font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; -webkit-font-smoothing:antialiased; }
              @media (max-width:620px) {
                .lb-container { width:100% !important; border-radius:0 !important; }
                .lb-pad { padding-left:22px !important; padding-right:22px !important; }
              }
            </style>
            </head>
            <body>
            <div class="lb-body" style="background:#e9eaed; padding:26px 12px;">
              <div style="display:none; max-height:0; overflow:hidden; opacity:0; color:#e9eaed; font-size:1px; line-height:1px;">{{preheader}}</div>
              <table role="presentation" class="lb-container" width="600" align="center" cellpadding="0" cellspacing="0" style="width:600px; max-width:600px; margin:0 auto; background:#ffffff; border-radius:18px; overflow:hidden; box-shadow:0 14px 40px rgba(12,19,32,0.14);">
                <tr>
                  <td style="background:#ffde5d; padding:18px 32px; text-align:center;">
                    <span style="font-size:20px; font-weight:600; letter-spacing:-0.01em; color:#111114;"><span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:#d62f2a; vertical-align:middle; margin-right:8px;"></span>LocalBuddy</span>
                  </td>
                </tr>
                <tr>
                  <td class="lb-pad" style="padding:32px;">{{content}}</td>
                </tr>
                <tr>
                  <td style="background:#111114; padding:32px; text-align:center;">
                    <span style="font-size:20px; font-weight:600; letter-spacing:-0.01em; color:#ffffff;"><span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:#d62f2a; vertical-align:middle; margin-right:8px;"></span>LocalBuddy</span>
                    <p style="margin:12px auto 0; max-width:340px; font-size:13px; line-height:1.6; color:rgba(255,255,255,0.55);">Real experiences with the locals who actually live here. Amsterdam born, Europe bound.</p>
                    <div style="height:1px; background:rgba(255,255,255,0.12); margin:18px 0 14px;"></div>
                    <p style="margin:0; font-size:12px; color:rgba(255,255,255,0.42);">&copy; LocalBuddy B.V. &nbsp;&middot;&nbsp; Amsterdam, The Netherlands</p>
                  </td>
                </tr>
              </table>
            </div>
            </body>
            </html>
            """;
}
