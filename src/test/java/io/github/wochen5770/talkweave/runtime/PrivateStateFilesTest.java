package io.github.wochen5770.talkweave.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PrivateStateFilesTest {
    @TempDir Path temp;
    @Test void restrictsAndAtomicallyReplacesFilesWithoutLeavingTemporaryCopies() throws Exception {
        Path dir = temp.resolve("private"); var files = new PrivateStateFiles(dir, false);
        files.writeJson("status.json", Map.of("phase", "WAIT"));
        files.writeJson("status.json", Map.of("phase", "CONNECTED"));
        assertThat(Files.readString(dir.resolve("status.json"))).contains("CONNECTED").doesNotContain("WAIT");
        try (var entries = Files.list(dir)) { assertThat(entries.toList()).containsExactly(dir.resolve("status.json")); }
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(dir.resolve("status.json"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        } else {
            var acl = Files.getFileAttributeView(dir.resolve("status.json"), AclFileAttributeView.class).getAcl();
            assertThat(acl).hasSize(1);
            assertThat(acl.getFirst().type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(acl.getFirst().principal()).isEqualTo(Files.getOwner(dir.resolve("status.json")));
        }
        assertThatThrownBy(() -> files.writeJson("../escape.json", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSymlinkChecksBeforeAccessingTargets() throws Exception {
        Path dir = temp.resolve("private"); var files = new PrivateStateFiles(dir, false);
        Path target = dir.resolve("status.json"); Files.writeString(target, "preserve");
        Path input = dir.resolve("verify-code.json"); Files.writeString(input, "preserve");
        try (var fs = org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fs.when(() -> Files.isSymbolicLink(target)).thenReturn(true);
            assertThatThrownBy(() -> files.writeJson("status.json", Map.of())).isInstanceOf(java.io.IOException.class);
            fs.when(() -> Files.isRegularFile(input, java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(false);
            assertThatThrownBy(() -> files.consumeVerification("challenge")).isInstanceOf(java.io.IOException.class);
            fs.when(() -> Files.isSymbolicLink(dir)).thenReturn(true);
            assertThatThrownBy(() -> new PrivateStateFiles(dir, false)).isInstanceOf(java.io.IOException.class);
        }
        assertThat(Files.readString(target)).isEqualTo("preserve");
        assertThat(Files.readString(input)).isEqualTo("preserve");
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    void rejectsNativeSymlinkDirectoryOutputAndInputWithoutChangingTarget() throws Exception {
        Path target = temp.resolve("original.json"); Files.writeString(target, "preserve");
        Path dir = temp.resolve("private"); var files = new PrivateStateFiles(dir, false);
        Files.createSymbolicLink(dir.resolve("verify-code.json"), target);
        assertThatThrownBy(() -> files.consumeVerification("challenge")).isInstanceOf(java.io.IOException.class);
        Files.createSymbolicLink(dir.resolve("status.json"), target);
        assertThatThrownBy(() -> files.writeJson("status.json", Map.of())).isInstanceOf(java.io.IOException.class);
        Path link = temp.resolve("linked-directory"); Files.createSymbolicLink(link, dir);
        assertThatThrownBy(() -> new PrivateStateFiles(link, false)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target)).isEqualTo("preserve");
    }
}
