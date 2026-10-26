package io.rentgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeviceKeyVault {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public DeviceKeyVault(@Value("${rent-gate.key-encryption-key:}") String configured,
                          @Value("${rent-gate.key-file:./data/device-key-encryption.key}") String location) {
        try {
            byte[] bytes;
            if (!configured.isBlank()) bytes = Base64.getDecoder().decode(configured);
            else {
                Path file = Path.of(location).toAbsolutePath();
                Files.createDirectories(file.getParent());
                if (!Files.exists(file)) {
                    byte[] generated = new byte[32]; random.nextBytes(generated);
                    try {
                        Files.writeString(file, Base64.getEncoder().encodeToString(generated), StandardOpenOption.CREATE_NEW);
                        var permissions = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
                        if (permissions != null) permissions.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    } catch (FileAlreadyExistsException concurrent) { /* Another context created it. */ }
                }
                bytes = Base64.getDecoder().decode(Files.readString(file).strip());
            }
            if (bytes.length != 32) throw new IllegalArgumentException("Expected a 32-byte encryption key");
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception error) { throw new IllegalStateException("Cannot load device key encryption key", error); }
    }

    public String encrypt(String deviceId, String value) { return transform(deviceId, value, true); }
    public String decrypt(String deviceId, String value) { return value == null ? null : transform(deviceId, value, false); }
    private String transform(String deviceId, String value, boolean encrypt) {
        try {
            byte[] input = encrypt ? value.getBytes(java.nio.charset.StandardCharsets.UTF_8) : Base64.getDecoder().decode(value);
            byte[] nonce = new byte[12];
            if (encrypt) random.nextBytes(nonce); else System.arraycopy(input, 0, nonce, 0, nonce.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(deviceId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!encrypt) return new String(cipher.doFinal(input, nonce.length, input.length - nonce.length), java.nio.charset.StandardCharsets.UTF_8);
            byte[] encrypted = cipher.doFinal(input), output = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length); System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception error) { throw new IllegalStateException("Cannot process stored device key", error); }
    }
}
