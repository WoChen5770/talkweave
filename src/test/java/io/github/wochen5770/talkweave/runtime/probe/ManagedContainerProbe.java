package io.github.wochen5770.talkweave.runtime.probe;

import io.github.wochen5770.talkweave.managed.binding.ManagedMaterials;
import io.github.wochen5770.talkweave.managed.persistence.ManagedProblem;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Offline artifact/material smoke only. External-service acceptance is a separate mandatory gate. */
public final class ManagedContainerProbe {
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException();
            switch(args[0]) {
                case "--artifact" -> artifact(Path.of(args[1]));
                case "--materials" -> materials(Path.of(args[1]));
                case "--legacy" -> legacy(Path.of(args[1]));
                default -> throw new IllegalArgumentException();
            }
            System.out.println("CI_MANAGED_OK mode=" + args[0] + " externalServices=NOT_RUN externalCalls=0");
        } catch(Exception failure) {
            System.err.println("CI_MANAGED_FAILED category=" + failure.getClass().getSimpleName()); System.exit(1);
        }
    }
    static void artifact(Path path) throws Exception {
        try(var jar = new ZipFile(path.toFile())) {
            var entries = jar.stream().map(e -> e.getName()).toList();
            if (entries.stream().anyMatch(n -> n.contains("/runtime/probe/") || n.toLowerCase(java.util.Locale.ROOT).contains("sqlite")
                    || n.contains("/db/migration/") || n.contains("/db/managed/")
                    || n.endsWith("/AssistantRuntime.class") || n.contains("/AssistantProperties")
                    || n.endsWith("/ConversationRepository.class") || n.endsWith("/ConversationWorker.class")
                    || n.endsWith("/LoginCoordinator.class") || n.endsWith("/IdentityVerifier.class")
                    || n.endsWith("/InboxReceiver.class") || n.endsWith("/WechatOutbound.class") || n.contains("Fixture")))
                throw new IllegalStateException("Retired or diagnostic content packaged");
            if (entries.stream().noneMatch(n -> n.endsWith("/managed/persistence/ManagedStore.class"))
                    || entries.stream().noneMatch(n -> n.contains("/mysql-connector-j-"))
                    || entries.stream().noneMatch(n -> n.endsWith("/db/mysql/V002__conversation_pagination.sql"))
                    || entries.stream().noneMatch(n -> n.endsWith("/runtime/HealthCheck.class")))
                throw new IllegalStateException("Required MySQL runtime missing");
        }
    }
    static void materials(Path path) throws Exception {
        Files.createDirectory(path); // Refuse existing directories, including real user materials.
        String installation = UUID.randomUUID().toString();
        try(var first = ManagedMaterials.open(path,installation)) { }
        try(var second = ManagedMaterials.open(path,installation)) { }
        if (!Files.getPosixFilePermissions(path).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            throw new IllegalStateException("Material directory not private");
        if (!Files.getPosixFilePermissions(path.resolve("mysql-materials-layout")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            throw new IllegalStateException("Material marker not private");
    }
    static void legacy(Path path) throws Exception {
        Files.createDirectory(path);
        Path old=path.resolve("talkweave-admin.sqlite"); Files.writeString(old,"synthetic-legacy-only",StandardOpenOption.CREATE_NEW);
        try { ManagedMaterials.inspect(path); throw new IllegalStateException("Legacy directory accepted"); }
        catch(ManagedProblem expected) { if(expected.code()!=ManagedProblem.Code.INCOMPATIBLE_LAYOUT) throw expected; }
        if(!Files.readString(old).equals("synthetic-legacy-only")) throw new IllegalStateException("Legacy material modified");
        try(var entries=Files.list(path)) { if(entries.count()!=1) throw new IllegalStateException("Legacy directory modified"); }
    }
}
