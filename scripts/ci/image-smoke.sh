#!/usr/bin/env bash
set -euo pipefail
image="${1:?image is required}"
platform="${2:?platform is required}"
case "$platform" in
  linux/amd64) arch=amd64 ;;
  linux/arm64) arch=arm64 ;;
  *) echo 'Unsupported CI architecture' >&2; exit 1 ;;
esac
volume="assistant-ci-${arch}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-${RANDOM}"
evidence="evidence/${arch}"
mkdir -p "$evidence"
trap 'docker volume rm "$volume" >/dev/null 2>&1 || true' EXIT
docker volume create "$volume" >/dev/null
common=(--rm --platform "$platform" --network none --read-only --cap-drop ALL
  --security-opt no-new-privileges:true --tmpfs /tmp:rw,exec,nosuid,nodev,size=128m,mode=1777
  --mount "type=volume,source=${volume},target=/app/data")
docker image inspect "$image" > "$evidence/image-inspect.json"
docker run "${common[@]}" --entrypoint sh "$image" -c 'id; test "$(id -u)" -ne 0' | tee "$evidence/identity.txt"
# Two fresh containers reuse only the temporary volume. No real configuration or network is provided.
for mode in --write --verify; do
  docker run "${common[@]}" --entrypoint java "$image" \
    -Dorg.sqlite.tmpdir=/tmp -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.ContainerStorageProbe \
    -cp /app/assistant.jar org.springframework.boot.loader.launch.PropertiesLauncher \
    "$mode" /app/data/ci-fixture "$arch" | tee "$evidence/storage-${mode#--}.txt"
done
