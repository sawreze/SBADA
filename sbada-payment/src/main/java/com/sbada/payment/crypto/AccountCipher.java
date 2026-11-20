package com.sbada.payment.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.payment.common.SecretFiles;

/**
 * 계좌번호 봉투 암호화(Envelope Encryption) — RSA + AES
 *
 *   계좌번호 ──AES-256-GCM(계좌마다 새 키, 새 IV)──▶ enc_account
 *   그 AES 키 ──RSA-3072 OAEP(SHA-256)  공개키로 감쌈──▶ enc_key
 *
 * 왜 bcrypt 가 아닌가: bcrypt 는 되돌릴 수 없는 해시라 '맞는지 확인'(비밀번호)에만 쓴다.
 *   계좌번호는 송금할 때 원래 번호가 필요하므로, 복호화할 수 있는 암호화를 써야 한다.
 * 왜 RSA 만 쓰지 않나: RSA 는 한 번에 짧은 데이터만, 느리게 암호화한다. 그래서 데이터는 AES 로,
 *   AES 키만 RSA 로 감싼다(TLS · 클라우드 KMS 와 같은 방식).
 * 안전한 점
 *   - DB 만 유출되면 계좌번호를 볼 수 없다: 풀려면 DB 밖(data/keys)의 RSA 개인키가 필요하다.
 *   - GCM 은 위·변조를 검사한다(한 글자라도 바뀌면 복호화 실패).
 *   - 중복 확인은 HMAC-SHA256(별도 키) 지문으로 한다. 지문으로는 번호를 알아낼 수 없다.
 *   - 운영에서는 개인키를 AWS KMS · HSM 같은 키 관리 서비스로 옮기면 서버 파일에도 남지 않는다.
 */
@Component
public class AccountCipher {

    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final String AES = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final SecretKeySpec hmacKey;

    public record Sealed(String encAccount, String encKey) {
    }

    public AccountCipher(@Value("${sbada.wallet.key-dir}") String keyDir) {
        Path dir = Path.of(keyDir);
        KeyPair pair = loadOrCreateRsa(dir.resolve("account-rsa-private.der"), dir.resolve("account-rsa-public.der"));
        this.publicKey = pair.getPublic();
        this.privateKey = pair.getPrivate();
        this.hmacKey = new SecretKeySpec(SecretFiles.loadOrCreate(dir.resolve("account-hmac.key"), 32), "HmacSHA256");
    }

    /** 계좌번호(숫자만) → 암호문 + 감싼 키 */
    public Sealed seal(String accountDigits) {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, random);
            SecretKey dataKey = gen.generateKey();

            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = aes.doFinal(accountDigits.getBytes(StandardCharsets.UTF_8));

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, OAEP);
            byte[] wrapped = rsa.doFinal(dataKey.getEncoded());

            byte[] packed = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
            return new Sealed(Base64.getEncoder().encodeToString(packed), Base64.getEncoder().encodeToString(wrapped));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호 암호화 실패", e);
        }
    }

    /** 송금 직전에만 쓴다: RSA 개인키로 AES 키를 풀고 계좌번호를 복원 */
    public String open(String encAccount, String encKey) {
        try {
            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.DECRYPT_MODE, privateKey, OAEP);
            byte[] keyBytes = rsa.doFinal(Base64.getDecoder().decode(encKey));

            byte[] packed = Base64.getDecoder().decode(encAccount);
            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            byte[] plain = aes.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            Arrays.fill(keyBytes, (byte) 0);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("계좌번호 복호화 실패(키가 다르거나 데이터가 변조됨)", e);
        }
    }

    /** 같은 계좌인지 비교용 지문(HMAC-SHA256, 16진수 64자). 지문에서 계좌번호를 되돌릴 수 없다 */
    public String fingerprint(String accountDigits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(accountDigits.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌 지문 계산 실패", e);
        }
    }

    private static KeyPair loadOrCreateRsa(Path privateFile, Path publicFile) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (Files.exists(privateFile) && Files.exists(publicFile)) {
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile)));
                return new KeyPair(pub, priv);
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(3072);
            KeyPair pair = gen.generateKeyPair();
            SecretFiles.write(privateFile, pair.getPrivate().getEncoded());
            SecretFiles.write(publicFile, pair.getPublic().getEncoded());
            return pair;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("계좌 암호화 키를 준비하지 못했습니다: " + privateFile.getParent(), e);
        }
    }
}
