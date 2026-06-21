package com.localbuddy.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.common.exception.BadRequestException;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Apple Wallet (.pkpass) provider. Builds an event-ticket pass for a booking and
 * signs it (PKCS#7 detached) with the issuer's Pass Type ID certificate and the
 * Apple WWDR certificate. Config-guarded: dormant until {@code app.wallet.apple.*}
 * (Pass Type ID, Team ID, the .p12 certificate, and the WWDR certificate) are set.
 */
@Service
public class AppleWalletService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String passTypeId;
    private final String teamId;
    private final String organizationName;
    private final String certificateBase64;
    private final String certificatePassword;
    private final String wwdrBase64;

    public AppleWalletService(
            @Value("${app.wallet.apple.pass-type-id:}") String passTypeId,
            @Value("${app.wallet.apple.team-id:}") String teamId,
            @Value("${app.wallet.apple.organization-name:LocalBuddy}") String organizationName,
            @Value("${app.wallet.apple.certificate-base64:}") String certificateBase64,
            @Value("${app.wallet.apple.certificate-password:}") String certificatePassword,
            @Value("${app.wallet.apple.wwdr-base64:}") String wwdrBase64) {
        this.passTypeId = passTypeId;
        this.teamId = teamId;
        this.organizationName = organizationName;
        this.certificateBase64 = certificateBase64;
        this.certificatePassword = certificatePassword;
        this.wwdrBase64 = wwdrBase64;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public boolean isConfigured() {
        return notBlank(passTypeId) && notBlank(teamId)
                && notBlank(certificateBase64) && notBlank(wwdrBase64);
    }

    /** Builds a signed .pkpass file for the booking. */
    public byte[] buildPkpass(WalletPassData data) {
        if (!isConfigured()) {
            throw new BadRequestException("Apple Wallet is not configured");
        }
        try {
            byte[] passJson = objectMapper.writeValueAsBytes(buildPassJson(data));
            byte[] icon = generateIcon(29);
            byte[] icon2x = generateIcon(58);

            Map<String, String> manifest = new LinkedHashMap<>();
            manifest.put("pass.json", sha1Hex(passJson));
            manifest.put("icon.png", sha1Hex(icon));
            manifest.put("icon@2x.png", sha1Hex(icon2x));
            byte[] manifestJson = objectMapper.writeValueAsBytes(manifest);

            byte[] signature = sign(manifestJson);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                addEntry(zip, "pass.json", passJson);
                addEntry(zip, "icon.png", icon);
                addEntry(zip, "icon@2x.png", icon2x);
                addEntry(zip, "manifest.json", manifestJson);
                addEntry(zip, "signature", signature);
            }
            return out.toByteArray();
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Unable to build Apple Wallet pass: " + ex.getMessage());
        }
    }

    private Map<String, Object> buildPassJson(WalletPassData data) {
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("formatVersion", 1);
        pass.put("passTypeIdentifier", passTypeId);
        pass.put("teamIdentifier", teamId);
        pass.put("organizationName", organizationName);
        pass.put("serialNumber", data.bookingReference());
        pass.put("description", "LocalBuddy booking " + data.bookingReference());
        if (data.startTime() != null) {
            pass.put("relevantDate", data.startTime().toString());
        }

        Map<String, Object> eventTicket = new LinkedHashMap<>();
        eventTicket.put("primaryFields", List.of(field("event", "Experience", data.experienceTitle())));

        var secondary = new java.util.ArrayList<Map<String, Object>>();
        if (data.startTime() != null) {
            secondary.add(field("date", "When", data.startTime().toString()));
        }
        if (data.location() != null) {
            secondary.add(field("location", "Where", data.location()));
        }
        eventTicket.put("secondaryFields", secondary);

        var auxiliary = new java.util.ArrayList<Map<String, Object>>();
        auxiliary.add(field("ref", "Booking", data.bookingReference()));
        auxiliary.add(field("guests", "Guests", String.valueOf(data.guests())));
        if (data.hostName() != null) {
            auxiliary.add(field("host", "Host", data.hostName()));
        }
        eventTicket.put("auxiliaryFields", auxiliary);

        pass.put("eventTicket", eventTicket);

        pass.put("barcode", Map.of(
                "format", "PKBarcodeFormatQR",
                "message", data.bookingReference(),
                "messageEncoding", "iso-8859-1"
        ));
        return pass;
    }

    private Map<String, Object> field(String key, String label, String value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("value", value == null ? "" : value);
        return f;
    }

    private byte[] sign(byte[] manifest) throws Exception {
        byte[] p12Bytes = Base64.getDecoder().decode(stripWhitespace(certificateBase64));
        byte[] wwdrBytes = Base64.getDecoder().decode(stripWhitespace(wwdrBase64));
        char[] password = (certificatePassword == null ? "" : certificatePassword).toCharArray();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(p12Bytes), password);

        String alias = null;
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (keyStore.isKeyEntry(candidate)) {
                alias = candidate;
                break;
            }
        }
        if (alias == null) {
            throw new BadRequestException("Apple Wallet certificate has no private key entry");
        }

        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
        X509Certificate signerCert = (X509Certificate) keyStore.getCertificate(alias);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate wwdrCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(wwdrBytes));

        JcaCertStore certStore = new JcaCertStore(List.of(signerCert, wwdrCert));

        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(privateKey);
        generator.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder()
                                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build())
                        .build(signer, signerCert));
        generator.addCertificates(certStore);

        CMSSignedData signedData = generator.generate(new CMSProcessableByteArray(manifest), false);
        return signedData.getEncoded();
    }

    private byte[] generateIcon(int size) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1E, 0x88, 0xE5));
        g.fillRect(0, 0, size, size);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private void addEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private String sha1Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private String stripWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s", "");
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
