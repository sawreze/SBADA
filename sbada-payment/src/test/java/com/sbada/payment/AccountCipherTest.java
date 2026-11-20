package com.sbada.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.sbada.payment.crypto.AccountCipher;

/** 봉투 암호화: 같은 번호도 매번 다른 암호문, 변조하면 복호화 실패, 다른 키로는 못 엶 */
class AccountCipherTest {

    @Test
    void roundTripAndTamperDetection() {
        AccountCipher cipher = new AccountCipher("./build/test-data/keys-unit");
        AccountCipher.Sealed a = cipher.seal("1002123456789");
        AccountCipher.Sealed b = cipher.seal("1002123456789");
        assertThat(a.encAccount()).isNotEqualTo(b.encAccount());   // 계좌마다 새 AES 키 · IV
        assertThat(cipher.open(a.encAccount(), a.encKey())).isEqualTo("1002123456789");
        assertThat(cipher.fingerprint("1002123456789")).isEqualTo(cipher.fingerprint("1002123456789"));

        byte[] bytes = Base64.getDecoder().decode(a.encAccount());
        bytes[bytes.length - 1] ^= 1; // 한 비트 변조
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cipher.open(tampered, a.encKey())).isInstanceOf(IllegalStateException.class);

        AccountCipher other = new AccountCipher("./build/test-data/keys-unit-other");
        assertThatThrownBy(() -> other.open(a.encAccount(), a.encKey())).isInstanceOf(IllegalStateException.class);
    }
}
