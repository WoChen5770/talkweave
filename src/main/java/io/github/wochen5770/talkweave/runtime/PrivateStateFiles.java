package io.github.wochen5770.talkweave.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/** Owner-only, flat local state files. No user-controlled file names or raw parser errors escape. */
public final class PrivateStateFiles {
    private static final Set<String> NAMES = Set.of("status.json", "identity.json", "health.json", "qr.png", "verify-code.json", "credentials.json", "evidence.json");
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();

    public PrivateStateFiles(Path directory, boolean requireNew) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        rejectSymlinks(this.directory);
        Files.createDirectories(this.directory.getParent());
        if (requireNew) Files.createDirectory(this.directory);
        else Files.createDirectories(this.directory);
        restrict(this.directory, true);
    }
    public void writeJson(String name, Object value) throws IOException { write(name, json.writeValueAsBytes(value)); }
    public void writeQr(String content) throws Exception {
        var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360,
                Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4));
        var image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x, y) ? 0 : 0xffffff);
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", bytes)) throw new IOException("PNG encoder unavailable");
        write("qr.png", bytes.toByteArray());
    }
    public void prepareVerification(String challengeId) throws IOException {
        if (!Files.exists(path("verify-code.json"), LinkOption.NOFOLLOW_LINKS)) {
            writeJson("verify-code.json", Map.of("challengeId", challengeId, "code", ""));
        }
    }
    public String consumeVerification(String challengeId) throws IOException {
        Path input = path("verify-code.json");
        if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.size(input) > 2048) throw new IOException("Unsafe verification input");
        restrict(input, false);
        byte[] bytes;
        try (var stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(2049); }
        if (bytes.length > 2048) throw new IOException("Unsafe verification input");
        JsonNode value;
        try { value = json.readTree(bytes); }
        catch (IOException ignored) { return null; } // The operator may still be editing.
        if (value == null || !value.isObject()) return null;
        String code = value.path("code").isTextual() ? value.path("code").asText() : "";
        if (code.isEmpty()) return null;
        Files.delete(input);
        if (!challengeId.equals(value.path("challengeId").asText()) || !code.matches("[0-9]{1,16}")) return null;
        return code;
    }
    public void clearLoginMaterials() throws IOException { remove("qr.png"); remove("verify-code.json"); }
    public void remove(String name) throws IOException { Files.deleteIfExists(path(name)); }
    private Path path(String name) throws IOException {
        rejectSymlinks(directory);
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown state artifact");
        return directory.resolve(name);
    }
    private void write(String name, byte[] bytes) throws IOException {
        rejectSymlinks(directory);
        Path target = path(name);
        if (Files.isSymbolicLink(target)) throw new IOException("Symlink rejected");
        Path temporary = Files.createTempFile(directory, ".state-", ".tmp");
        try {
            restrict(temporary, false);
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Owner-only permissions unavailable");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }
    private static void rejectSymlinks(Path path) throws IOException {
        Path part = path.getRoot();
        for (Path component : path) {
            part = part.resolve(component);
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink rejected");
        }
    }
}