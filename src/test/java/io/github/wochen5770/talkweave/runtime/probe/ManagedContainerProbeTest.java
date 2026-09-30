package io.github.wochen5770.talkweave.runtime.probe;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedContainerProbeTest {
    @TempDir Path directory;
    @Test void preservesLegacyMaterialsAndRefusesExistingFixtureTargets() throws Exception {
        Path old=directory.resolve("old"); ManagedContainerProbe.legacy(old);
        assertEquals("synthetic-legacy-only",Files.readString(old.resolve("talkweave-admin.sqlite")));
        assertThrows(FileAlreadyExistsException.class, () -> ManagedContainerProbe.materials(directory));
    }
    @Test void rejectsNonJarArtifacts() { assertThrows(Exception.class, () -> ManagedContainerProbe.artifact(directory)); }
}
