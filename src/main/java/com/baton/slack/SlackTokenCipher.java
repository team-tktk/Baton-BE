package com.baton.slack;
import java.nio.charset.StandardCharsets; import java.security.*; import java.util.Base64; import javax.crypto.*; import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component;
@Component class SlackTokenCipher {
 private final byte[] key; SlackTokenCipher(@Value("${app.slack.token-encryption-key:}") String raw){key=raw.isBlank()?null:Base64.getDecoder().decode(raw);}
 String encrypt(String value){check();try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 String decrypt(String value){check();try{String[] p=value.split("\\.");byte[] iv=Base64.getDecoder().decode(p[0]);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
 private void check(){if(key==null||(key.length!=16&&key.length!=24&&key.length!=32))throw new IllegalStateException("SLACK_TOKEN_ENCRYPTION_KEY must be a base64 AES key");}
}
