package io.github.wochen5770.talkweave.managed.binding;

import io.github.wochen5770.talkweave.managed.persistence.ManagedProblem;
import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Private ephemeral materials, never a local business database or a legacy deployment directory. */
public final class ManagedMaterials implements AutoCloseable {
    private static final String MARKER = "mysql-materials-layout";
    private static final String PREFIX = "talkweave-mysql-materials:1\n";
    private final FileChannel channel;
    private final FileLock lock;
    private ManagedMaterials(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    public static void inspect(Path path) {
        try {
            check(path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            try (var entries = Files.list(path)) {
                var files = entries.toList();
                if (files.isEmpty()) return;
                for (var file : files) {
                    check(file);
                    if (!java.util.Set.of(MARKER, "materials.lock", "binding-materials").contains(file.getFileName().toString()))
                        throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
                }
                marker(path);
            }
        } catch (IOException failure) { throw new ManagedProblem(INVALID_DIRECTORY); }
    }
    public static ManagedMaterials open(Path path, String installationId) {
        inspect(path);
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Path marker = path.resolve(MARKER);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS) && !marker(path).equals(installationId))
                throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
            Files.createDirectories(path); PrivateStateFiles.restrict(path, true);
            channel = FileChannel.open(path.resolve("materials.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new ManagedProblem(DIRECTORY_IN_USE); }
            if (lock == null) throw new ManagedProblem(DIRECTORY_IN_USE);
            PrivateStateFiles.restrict(path.resolve("materials.lock"), false);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(marker); PrivateStateFiles.restrict(marker, false);
                Files.writeString(marker, PREFIX + UUID.fromString(installationId) + "\n", StandardOpenOption.TRUNCATE_EXISTING);
            } else if (!marker(path).equals(installationId)) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
            return new ManagedMaterials(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try { if (lock != null) lock.release(); } catch (IOException ignored) { }
            try { if (channel != null) channel.close(); } catch (IOException ignored) { }
            if (failure instanceof ManagedProblem problem) throw problem;
            throw new ManagedProblem(INVALID_DIRECTORY);
        }
    }
    private static String marker(Path path) throws IOException {
        Path file = path.resolve(MARKER); check(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        String value = Files.readString(file);
        if (!value.startsWith(PREFIX) || !value.endsWith("\n")) throw new ManagedProblem(INCOMPATIBLE_LAYOUT);
        String id = value.substring(PREFIX.length(), value.length() - 1);
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw new ManagedProblem(INCOMPATIBLE_LAYOUT); }
        return id;
    }
    private static void check(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part) || (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException();
        }
    }
    @Override public void close() {
        try { lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
    }
}
