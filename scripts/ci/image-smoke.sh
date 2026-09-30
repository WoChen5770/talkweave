#!/usr/bin/env bash
set -euo pipefail
image="${1:?image is required}"
platform="${2:?platform is required}"
diagnostics="$(realpath "${3:?explicit diagnostics JAR is required}")"
case "$platform" in
  linux/amd64) arch=amd64 ;;
  linux/arm64) arch=arm64 ;;
  *) echo 'Unsupported CI architecture' >&2; exit 1 ;;
esac
evidence="evidence/$arch"
mkdir -p "$evidence"
owned_volumes=()
cleanup() {
  for volume in "${owned_volumes[@]}"; do docker volume rm "$volume" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT
docker image inspect "$image" > "$evidence/image-inspect.json"
probe=(-Dloader.path=/diagnostics.jar -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.ManagedContainerProbe
  -cp /app/assistant.jar org.springframework.boot.loader.launch.PropertiesLauncher)
common=(--rm --platform "$platform" --network none --read-only --cap-drop ALL
  --security-opt no-new-privileges:true --tmpfs /tmp:rw,exec,nosuid,nodev,size=128m,mode=1777
  --mount "type=bind,source=$diagnostics,target=/diagnostics.jar,readonly")
docker run "${common[@]}" --entrypoint sh "$image" -c 'id; test "$(id -u)" -ne 0' > "$evidence/identity.txt"
docker run "${common[@]}" --entrypoint java "$image" "${probe[@]}" --artifact /app/assistant.jar > "$evidence/artifact.txt"
for uid in 0 10001; do
  volume="talkweave-ci-$arch-$uid-${GITHUB_RUN_ID:-local}-$RANDOM-$RANDOM"
  docker volume create "$volume" >/dev/null
  owned_volumes+=("$volume")
  docker run --rm --platform "$platform" --network none --user 0:0 \
    --mount "type=volume,source=$volume,target=/app/materials" --entrypoint sh "$image" \
    -c "chown $uid:$uid /app/materials; chmod 700 /app/materials"
  mounted=(--user "$uid:$uid" --mount "type=volume,source=$volume,target=/app/materials,volume-nocopy")
  docker run "${common[@]}" "${mounted[@]}" --entrypoint java "$image" "${probe[@]}" --materials /app/materials/fixture > "$evidence/materials-$uid.txt"
  docker run "${common[@]}" "${mounted[@]}" --entrypoint java "$image" "${probe[@]}" --legacy /app/materials/legacy > "$evidence/legacy-$uid.txt"
done
# This offline stage cannot authorize publication or pretend to exercise remote engines.
printf 'offlineArtifactAndMaterials=PASS\nexternalServices=NOT_RUN\n' > "$evidence/acceptance.txt"
