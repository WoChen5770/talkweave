package io.github.wochen5770.talkweave.managed.binding;

import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import io.github.wochen5770.talkweave.managed.persistence.ManagedProblem;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Flat private attempt directories. No API accepts a path and no recursive deletion is used. */
public final class BindingMaterials {
    private final Path root;
    public BindingMaterials(Path managedDirectory) {
        root = managedDirectory.toAbsolutePath().normalize().resolve("binding-materials");
        try {
            check(root); Files.createDirectories(root); PrivateStateFiles.restrict(root, true);
            // The store has already cancelled unfinished tasks. Challenges never survive restart.
            try (var dirs = Files.newDirectoryStream(root)) {
                for (Path dir : dirs) {
                    String id = dir.getFileName().toString(); path(id);
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected binding material");
                    clear(id);
                }
            }
        } catch (IOException failure) { throw new ManagedProblem(INVALID_DIRECTORY); }
    }
    public void create(String id) {
        try { new PrivateStateFiles(path(id), true); }
        catch (IOException failure) { throw new ManagedProblem(INVALID_DIRECTORY); }
    }
    public void writeQr(String id, String content) {
        if (content == null || content.isBlank() || content.length() > 4096) throw new ManagedProblem(INVALID_INPUT);
        try { new PrivateStateFiles(path(id), false).writeQr(content); }
        catch (Exception failure) { throw new ManagedProblem(INVALID_DIRECTORY); }
    }
    public byte[] image(String id) {
        try {
            Path qr = path(id).resolve("qr.png"); check(qr);
            if (!Files.isRegularFile(qr, LinkOption.NOFOLLOW_LINKS)) throw new ManagedProblem(NOT_FOUND);
            try (var input = Files.newInputStream(qr, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new IOException("Oversized image");
                return bytes;
            }
        } catch (IOException failure) { throw new ManagedProblem(INVALID_DIRECTORY); }
    }
    public void clear(String id) {
        try {
            Path directory = path(id);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            // Prevalidate every child before deleting anything. Only files this component creates are eligible.
            try (var paths = Files.newDirectoryStream(directory)) {
                for (var child : paths) {
                    String name = child.getFileName().toString(); check(child);
                    if ((!name.equals("qr.png") && !name.matches("[.]state-[A-Za-z0-9-]+[.]tmp"))
                            || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected material file");
                }
            }
            try (var paths = Files.newDirectoryStream(directory)) { for (var child : paths) Files.delete(child); }
            Files.delete(directory);
        } catch (IOException failure) { throw new ManagedProblem(INVALID_DIRECTORY); }
    }
    private Path path(String id) throws IOException {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException failure) { throw new ManagedProblem(INVALID_INPUT); }
        Path path = root.resolve(id).normalize();
        if (!path.getParent().equals(root)) throw new IOException("Invalid material scope");
        check(path); return path;
    }
    private static void check(Path path) throws IOException {
        Path part = path.getRoot();
        for (Path segment : path) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part) || (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("Linked material path rejected");
        }
    }
}
