package io.github.wochen5770.talkweave.runtime.probe;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ManagedContainerProbeTest {
    @TempDir Path directory;
    @Test void managedSettingsAndUsersSurviveReplacementWithPrivatePermissions() throws Exception {
        Path fixture = directory.resolve("fixture");
        ManagedContainerProbe.storage("--write", fixture);
        ManagedContainerProbe.storage("--verify", fixture);
        assertThatThrownBy(() -> ManagedContainerProbe.storage("--write", fixture))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }
    @Test void refusesUnmarkedDirectoriesAndInvalidModes() {
        assertThatThrownBy(() -> ManagedContainerProbe.storage("--verify", directory)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> ManagedContainerProbe.storage("--other", directory)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyRejectionPreservesContentsAndPermissions() throws Exception {
        ManagedContainerProbe.legacy(directory.resolve("legacy"));
    }
}
