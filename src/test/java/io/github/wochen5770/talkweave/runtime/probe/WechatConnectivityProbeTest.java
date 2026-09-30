package io.github.wochen5770.talkweave.runtime.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.Incoming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WechatConnectivityProbeTest {
    @TempDir Path temp;
    @Test void onlyExactPrivateNonceAndValidPrivateMessageMayTriggerTheSingleReply() {
        var valid = new Incoming("1", "synthetic-user", "synthetic-context", "wx-check-random", false, 1, 2);
        assertThat(ProbeMessages.matches("wx-check-random", valid)).isTrue();
        assertThat(ProbeMessages.matches("other-nonce", valid)).isFalse();
        assertThat(ProbeMessages.matches("wx-check-random", new Incoming("1", "u", "c", "wx-check-random", true, 1, 2))).isFalse();
        assertThat(ProbeMessages.matches("wx-check-random", new Incoming(null, "u", "c", "wx-check-random", false, 1, 2))).isFalse();
        assertThat(ProbeMessages.matches("wx-check-random", new Incoming("1", "u", "", "wx-check-random", false, 1, 2))).isFalse();
        assertThat(ProbeMessages.matches("wx-check-random", new Incoming("1", "u", "c", "wx-check-random", false, 2, 2))).isFalse();
    }
    @Test void privateQrRoundTripsAndVerificationIsChallengeBoundAndOneTime() throws Exception {
        Path directory = temp.resolve("probe");
        var files = new PrivateProbeFiles(directory);
        files.writeQr("https://example.invalid/synthetic-login");
        var image = ImageIO.read(directory.resolve("qr.png").toFile());
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels))));
        assertThat(decoded.getText()).isEqualTo("https://example.invalid/synthetic-login");
        files.prepareVerification("current");
        assertThat(files.consumeVerification("current")).isNull();
        files.writeJson("verify-code.json", Map.of("challengeId", "stale", "code", "123456"));
        assertThat(files.consumeVerification("current")).isNull();
        files.writeJson("verify-code.json", Map.of("challengeId", "current", "code", "123456"));
        assertThat(files.consumeVerification("current")).isEqualTo("123456");
        assertThat(files.consumeVerification("current")).isNull();
        files.writeJson("status.json", Map.of("phase", "COMPLETED"));
        assertThat(new ObjectMapper().readTree(Files.readAllBytes(directory.resolve("status.json"))).path("phase").asText()).isEqualTo("COMPLETED");
        files.clearLoginMaterials();
        assertThat(directory.resolve("qr.png")).doesNotExist();
        assertThatThrownBy(() -> new PrivateProbeFiles(directory)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }
}
