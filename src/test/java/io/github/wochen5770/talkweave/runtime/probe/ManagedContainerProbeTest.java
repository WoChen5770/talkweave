package io.github.wochen5770.talkweave.runtime.probe;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
    private static final List<String> REQUIRED = List.of(
            "BOOT-INF/classes/io/github/wochen5770/talkweave/managed/persistence/ManagedStore.class",
            "BOOT-INF/lib/mysql-connector-j-9.6.0.jar",
            "BOOT-INF/classes/db/mysql/V002__conversation_pagination.sql",
            "BOOT-INF/classes/io/github/wochen5770/talkweave/runtime/HealthCheck.class");
    private Path jar(List<String> entries) throws Exception {
        Path file = directory.resolve(java.util.UUID.randomUUID() + ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : entries) { zip.putNextEntry(new ZipEntry(name)); zip.closeEntry(); }
        }
        return file;
    }
    @Test void requiresCurrentRuntimeAndRejectsRetiredResourcesAndDiagnosticEntrypoints() throws Exception {
        assertDoesNotThrow(() -> ManagedContainerProbe.artifact(jar(REQUIRED)));
        for (String required : REQUIRED) {
            var missing = new ArrayList<>(REQUIRED); missing.remove(required);
            assertThrows(IllegalStateException.class, () -> ManagedContainerProbe.artifact(jar(missing)), required);
        }
        for (String retired : List.of("BOOT-INF/lib/sqlite-jdbc.jar", "BOOT-INF/classes/db/migration/old.sql",
                "BOOT-INF/classes/db/managed/old.sql", "BOOT-INF/classes/runtime/probe/ModelConnectivityProbe.class",
                "BOOT-INF/classes/runtime/AssistantProperties$Model.class", "BOOT-INF/classes/channel/LoginCoordinator.class",
                "BOOT-INF/classes/managed/ManagedBrowserFixture.class")) {
            var invalid = new ArrayList<>(REQUIRED); invalid.add(retired);
            assertThrows(IllegalStateException.class, () -> ManagedContainerProbe.artifact(jar(invalid)), retired);
        }
    }
}
