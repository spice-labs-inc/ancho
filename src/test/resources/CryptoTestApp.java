import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;

/**
 * Minimal app that exercises various crypto APIs for testing runtime surveys.
 * Run with: java CryptoTestApp.java (JDK 11+ single-file source)
 */
public class CryptoTestApp {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Crypto Test App ===");

        // AES (TRANSITION)
        KeyGenerator aesGen = KeyGenerator.getInstance("AES");
        aesGen.init(256);
        SecretKey aesKey = aesGen.generateKey();
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey);
        aesCipher.doFinal("hello quantum world".getBytes());
        System.out.println("AES-256 encrypt: OK");

        // SHA-256 (GREEN)
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.digest("test data".getBytes());
        System.out.println("SHA-256 digest: OK");

        // RSA (YELLOW)
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        KeyPair rsaKey = rsaGen.generateKeyPair();
        Signature rsaSig = Signature.getInstance("SHA256withRSA");
        rsaSig.initSign(rsaKey.getPrivate());
        rsaSig.update("sign me".getBytes());
        rsaSig.sign();
        System.out.println("RSA-2048 sign: OK");

        // MD5 (RED)
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.digest("bad hash".getBytes());
        System.out.println("MD5 digest: OK");

        System.out.println("=== Done ===");
    }
}
