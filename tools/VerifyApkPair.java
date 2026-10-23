import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/** Build-time verification only; this file is outside every application source set. */
public class VerifyApkPair {
    private static Set<String> verifiedCertificates(String path) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(path)).build().verify();
        if (!result.isVerified()) {
            throw new IllegalStateException("APK signature verification failed: " + path + " " + result.getErrors());
        }
        Set<String> certificates = new TreeSet<>();
        for (X509Certificate certificate : result.getSignerCertificates()) {
            byte[] encoded = certificate.getEncoded();
            certificates.add(Base64.getEncoder().encodeToString(encoded));
            System.out.println(path + " certificate SHA-256: "
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
        }
        if (certificates.isEmpty()) throw new IllegalStateException("No verified signer certificate: " + path);
        return certificates;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected upstream and Chinese APK paths");
        Set<String> upstream = verifiedCertificates(args[0]);
        Set<String> chinese = verifiedCertificates(args[1]);
        if (!upstream.equals(chinese)) throw new IllegalStateException("Control and Chinese signing certificates differ");
        System.out.println("PASS: both APK signatures verify and complete signer certificates match byte for byte.");
    }
}
