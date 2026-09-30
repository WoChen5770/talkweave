package io.github.wochen5770.talkweave.managed.binding;

import io.github.wochen5770.talkweave.managed.persistence.ManagedProblem;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedMaterialsTest {
    @TempDir Path directory;
    @Test void freshPrivateMaterialsReopenOnlyForTheSameInstallation() throws Exception {
        String installation = UUID.randomUUID().toString();
        try (var first = ManagedMaterials.open(directory,installation)) {
            assertEquals(ManagedProblem.Code.DIRECTORY_IN_USE,assertThrows(ManagedProblem.class, () -> ManagedMaterials.open(directory,installation)).code());
            assertFalse(Files.exists(directory.resolve("talkweave-admin.sqlite")));
            if(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory));
        }
        try(var reopened = ManagedMaterials.open(directory,installation)) { assertNotNull(reopened); }
        assertEquals(ManagedProblem.Code.INCOMPATIBLE_LAYOUT,assertThrows(ManagedProblem.class, () -> ManagedMaterials.open(directory,UUID.randomUUID().toString())).code());
    }
    @Test void oldDataAndUnknownFilesAreRejectedBeforeAnyWrite() throws Exception {
        Path file = directory.resolve("talkweave-admin.sqlite"); Files.writeString(file,"synthetic-old-data");
        assertEquals(ManagedProblem.Code.INCOMPATIBLE_LAYOUT,assertThrows(ManagedProblem.class, () -> ManagedMaterials.open(directory,UUID.randomUUID().toString())).code());
        assertEquals("synthetic-old-data",Files.readString(file));
        try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
    }
    @Test void incompleteMarkerIsNotRewrittenOrCleaned() throws Exception {
        var marker=directory.resolve("mysql-materials-layout"); Files.writeString(marker,"interrupted");
        assertThrows(ManagedProblem.class, () -> ManagedMaterials.inspect(directory));
        assertEquals("interrupted",Files.readString(marker));
        assertFalse(Files.exists(directory.resolve("materials.lock")));
    }
}
