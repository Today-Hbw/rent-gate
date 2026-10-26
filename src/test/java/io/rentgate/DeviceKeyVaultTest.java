package io.rentgate;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DeviceKeyVaultTest {
    @TempDir Path directory;
    @Test void persistentEncryptionKeySurvivesRestartAndCipherIsBoundToDevice() {
        String file=directory.resolve("keys/device.key").toString();
        var first=new DeviceKeyVault("",file);
        String encrypted=first.encrypt("device-a","secret-device-key");
        assertThat(encrypted).doesNotContain("secret-device-key");
        var restarted=new DeviceKeyVault("",file);
        assertThat(restarted.decrypt("device-a",encrypted)).isEqualTo("secret-device-key");
        assertThatThrownBy(()->restarted.decrypt("device-b",encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
