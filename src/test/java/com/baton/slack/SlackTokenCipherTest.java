package com.baton.slack;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Base64; import org.junit.jupiter.api.Test;
class SlackTokenCipherTest {
 @Test void encryptsAndDecryptsBotToken(){String key=Base64.getEncoder().encodeToString(new byte[32]);var cipher=new SlackTokenCipher(key);String encrypted=cipher.encrypt("xoxb-secret");assertThat(encrypted).doesNotContain("xoxb-secret");assertThat(cipher.decrypt(encrypted)).isEqualTo("xoxb-secret");}
}
