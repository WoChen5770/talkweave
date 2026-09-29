package io.github.personalassistant.runtime.probe;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ContainerStorageProbeTest {
    @TempDir Path temp;
    @Test void closesAndReopensPersistentSyntheticHistory() throws Exception {
        var directory = temp.resolve("fixture");
        ContainerStorageProbe.run("--write", directory);
        ContainerStorageProbe.run("--verify", directory);
        assertThat(directory.resolve("assistant.sqlite")).exists();
    }
    @Test void refusesExistingOrUnmarkedDataWithoutInitializingStorage() throws Exception {
        var directory = temp.resolve("existing"); Files.createDirectory(directory);
        Files.writeString(directory.resolve("preserve.txt"), "preserve");
        assertThatThrownBy(() -> ContainerStorageProbe.run("--write", directory)).isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> ContainerStorageProbe.run("--verify", directory)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(directory.resolve("preserve.txt"))).isEqualTo("preserve");
        assertThat(directory.resolve("assistant.sqlite")).doesNotExist();
    }
    @Test void rejectsUnknownModesAndMalformedMarkers() throws Exception {
        var directory = temp.resolve("fixture"); Files.createDirectory(directory);
        assertThatThrownBy(() -> ContainerStorageProbe.run("--other", directory)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(directory.resolve("ci-storage-fixture.txt"), "x".repeat(300));
        assertThatThrownBy(() -> ContainerStorageProbe.run("--verify", directory)).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("assistant.sqlite")).doesNotExist();
    }
}
