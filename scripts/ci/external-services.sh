#!/usr/bin/env bash
# GitHub-hosted ephemeral runner only. Never invoke against an operator's workstation firewall.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || { echo 'Requires an isolated GitHub-hosted runner' >&2; exit 1; }
: "${EXTERNAL_SERVICES_YAML:?Separately authorized CI configuration is required}"
: "${APPROVED_MYSQL_SCHEMA:?}" "${APPROVED_REDIS_PREFIX:?}" "${EXPECTED_MYSQL_VERSION:?}" "${EXPECTED_REDIS_VERSION:?}"
umask 077
work=$(mktemp -d "${RUNNER_TEMP:?}/talkweave-external.XXXXXXXX")
chain="TW_IT_$$"
installed=false
cleanup() {
  if [[ "$installed" == true ]]; then
    sudo iptables -D OUTPUT -j "$chain" || true
    sudo ip6tables -D OUTPUT -j "$chain" || true
  fi
  sudo iptables -F "$chain" 2>/dev/null || true
  sudo iptables -X "$chain" 2>/dev/null || true
  sudo ip6tables -F "$chain" 2>/dev/null || true
  sudo ip6tables -X "$chain" 2>/dev/null || true
  # Only files created by this invocation; never a shared cache/schema cleanup.
  rm -f "$work/config.yml" "$work/targets" "$work/classpath"
  rmdir "$work"
}
trap cleanup EXIT
printf '%s' "$EXTERNAL_SERVICES_YAML" > "$work/config.yml"
unset EXTERNAL_SERVICES_YAML
approval=(-Dtalkweave.it.enabled=true "-Dtalkweave.it.config=$work/config.yml"
  "-Dtalkweave.it.mysql-schema=$APPROVED_MYSQL_SCHEMA" "-Dtalkweave.it.redis-prefix=$APPROVED_REDIS_PREFIX"
  "-Dtalkweave.it.mysql-version=$EXPECTED_MYSQL_VERSION" "-Dtalkweave.it.redis-version=$EXPECTED_REDIS_VERSION"
  -Dtalkweave.it.allow-schema-initialization=true -Dtalkweave.it.allow-schema-upgrade=true)
# Run the no-external-service unit suite before restricting egress, warming the actual
# Surefire/JUnit provider as well as compilation dependencies on a fresh runner.
# Secrets are never supplied as Maven arguments; this does not enable the IT profile.
mvn -B test org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath "-Dmdep.outputFile=$work/classpath"
java "${approval[@]}" -cp "target/test-classes:target/classes:$(cat "$work/classpath")" \
  io.github.wochen5770.talkweave.managed.config.ExternalNetworkTargets > "$work/targets"
sudo iptables -N "$chain"
sudo ip6tables -N "$chain"
for firewall in iptables ip6tables; do
  sudo "$firewall" -A "$chain" -o lo -j ACCEPT
  sudo "$firewall" -A "$chain" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
done
while read -r address port; do sudo iptables -A "$chain" -p tcp -d "$address" --dport "$port" -j ACCEPT; done < "$work/targets"
sudo iptables -A "$chain" -j REJECT
sudo ip6tables -A "$chain" -j REJECT
installed=true
sudo iptables -I OUTPUT 1 -j "$chain"
sudo ip6tables -I OUTPUT 1 -j "$chain"
mvn -o -B -Pexternal-services "${approval[@]}" '-Dtest=*IT,!HistoryBenchmarkIT' test
printf 'externalServices=PASS\nnetwork=explicit-data-targets-and-loopback-only\n' > target/external-acceptance.txt
