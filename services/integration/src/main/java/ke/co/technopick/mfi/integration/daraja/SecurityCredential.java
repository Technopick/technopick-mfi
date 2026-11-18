package ke.co.technopick.mfi.integration.daraja;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * Daraja SecurityCredential: the initiator password encrypted with the public key from Safaricom's
 * certificate for the environment (RSA, PKCS#1 v1.5 padding), Base64-encoded. Accepts a PEM
 * certificate, or a PEM public key (used by the TEST-ONLY simulator).
 */
public final class SecurityCredential {

    private SecurityCredential() {
    }

    public static String encrypt(String pem, String initiatorPassword) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey(pem));
            return Base64.getEncoder().encodeToString(cipher.doFinal(initiatorPassword.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build the Daraja SecurityCredential", e);
        }
    }

    static PublicKey publicKey(String pem) throws GeneralSecurityException {
        if (pem == null || pem.isBlank()) {
            throw new GeneralSecurityException("no Daraja certificate configured");
        }
        if (pem.contains("BEGIN CERTIFICATE")) {
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))).getPublicKey();
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
