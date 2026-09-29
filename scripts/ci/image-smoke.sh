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
managed_volumes=()
managed_containers=()
cleanup() {
  for container in "${managed_containers[@]}"; do docker rm -f "$container" >/dev/null 2>&1 || true; done
  for data in "${managed_volumes[@]}"; do docker volume rm "$data" >/dev/null 2>&1 || true; done
  docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT
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

# Independent disposable volumes model both Compose root and an overridden non-root UID.
for uid in 0 10001; do
  data="${volume}-managed-${uid}"
  container="${data}-web"
  managed_volumes+=("$data")
  managed_containers+=("$container")
  docker volume create "$data" >/dev/null
  # CI fixture preparation only; the production application never recursively chowns a mount.
  docker run --rm --platform "$platform" --network none --user 0:0 \
    --mount "type=volume,source=${data},target=/app/data" --entrypoint sh "$image" \
    -c "chown ${uid}:${uid} /app/data; chmod 700 /app/data"
  managed=(--platform "$platform" --network none --read-only --user "${uid}:${uid}" --cap-drop ALL
    --security-opt no-new-privileges:true --tmpfs /tmp:rw,exec,nosuid,nodev,size=128m,mode=1777
    --mount "type=volume,source=${data},target=/app/data")
  probe=(-Dorg.sqlite.tmpdir=/tmp -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.ManagedContainerProbe
    -cp /app/assistant.jar org.springframework.boot.loader.launch.PropertiesLauncher)
  for mode in --write --verify; do
    docker run --rm "${managed[@]}" --entrypoint java "$image" "${probe[@]}" \
      "$mode" /app/data/managed-fixture | tee "$evidence/managed-${uid}-${mode#--}.txt"
  done
  docker run --rm "${managed[@]}" --entrypoint java "$image" "${probe[@]}" \
    --legacy /app/data/legacy-fixture | tee "$evidence/managed-${uid}-legacy-rejection.txt"
  docker run -d "${managed[@]}" --name "$container" \
    -e MANAGED_DATA_DIR=/app/data/web -e ADMIN_INIT_USERNAME=ci-admin \
    -e ADMIN_INIT_PASSWORD=synthetic-container-password "$image" >/dev/null
  ready=false
  for attempt in {1..90}; do
    if docker exec "$container" java -Dloader.main=io.github.wochen5770.talkweave.runtime.HealthCheck \
      -cp /app/assistant.jar org.springframework.boot.loader.launch.PropertiesLauncher >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then echo 'Managed Web startup failed' >&2; exit 1; fi
  docker exec "$container" java "${probe[@]}" --web | tee "$evidence/managed-${uid}-web.txt"
  started=$SECONDS
  docker stop "$container" >/dev/null
  elapsed=$((SECONDS - started))
  code=$(docker inspect --format '{{.State.ExitCode}}' "$container")
  [[ "$code" != 137 && "$elapsed" -le 8 ]]
  printf 'shutdownSeconds=%s\nexitCode=%s\n' "$elapsed" "$code" > "$evidence/managed-${uid}-stop.txt"
  # Recreate with no bootstrap secret; persisted administrator must still authenticate.
  docker rm "$container" >/dev/null
  docker run -d "${managed[@]}" --name "$container" -e MANAGED_DATA_DIR=/app/data/web "$image" >/dev/null
  ready=false
  for attempt in {1..90}; do
    if docker exec "$container" java -Dloader.main=io.github.wochen5770.talkweave.runtime.HealthCheck \
      -cp /app/assistant.jar org.springframework.boot.loader.launch.PropertiesLauncher >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 1
  done
  [[ "$ready" == true ]]
  docker exec "$container" java "${probe[@]}" --web | tee "$evidence/managed-${uid}-restart.txt"
  docker stop "$container" >/dev/null
done
