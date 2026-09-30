package io.github.wochen5770.talkweave.runtime.probe;

import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import java.io.IOException;
import java.nio.file.Path;

/** A probe deliberately cannot resume an existing run directory. */
public final class PrivateProbeFiles {
    private final PrivateStateFiles files;
    public PrivateProbeFiles(Path directory) throws IOException { files = new PrivateStateFiles(directory, true); }
    public void writeJson(String name, Object value) throws IOException { files.writeJson(name, value); }
    public void writeQr(String content) throws Exception { files.writeQr(content); }
    public void prepareVerification(String challengeId) throws IOException { files.prepareVerification(challengeId); }
    public String consumeVerification(String challengeId) throws IOException { return files.consumeVerification(challengeId); }
    public void clearLoginMaterials() throws IOException { files.clearLoginMaterials(); }
}
