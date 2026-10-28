#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
trap 'printf "Update berhenti di baris %s; periksa log di atas.\n" "$LINENO" >&2' ERR
target=${1:?Pass the published main commit (40 hex characters)}
[[ "$target" =~ ^[a-f0-9]{40}$ ]]
[ "$(id -u)" = 0 ] || { printf 'Jalankan sebagai root.\n' >&2; exit 1; }
cd /var/www/crm-be
exec 9>/var/lock/crm-main-deploy.lock
flock -n 9 || { printf 'Deploy CRM lain masih berjalan.\n' >&2; exit 1; }
compose=(docker compose -p crm-kimtele --project-directory "$PWD" -f "$PWD/docker-compose.yml" -f "$PWD/docker-compose.override.yml")
url=https://github.com/josephthewarrior1/Crm_Kimcommunication_be.git
export GIT_TERMINAL_PROMPT=0
test "$(git symbolic-ref --short HEAD)" = main
git diff --quiet HEAD -- src pom.xml Dockerfile .dockerignore
test -z "$(git ls-files --others --exclude-standard -- src)"
git fetch --no-tags "$url" main
test "$(git rev-parse FETCH_HEAD)" = "$target"
git merge-base --is-ancestor HEAD "$target"
while IFS= read -r file; do
  case "$file" in
    src/main/java/com/crm/service/EmsV2Client.java|src/main/java/com/crm/service/EmsService.java|src/test/java/com/crm/service/EmsV2ClientTest.java|src/test/java/com/crm/service/EmsServiceRoutingTest.java|scripts/deploy-ems-industry.sh) ;;
    *) printf 'Perubahan di luar perbaikan industry: %s\n' "$file" >&2; exit 1 ;;
  esac
done < <(git diff --name-only HEAD "$target")
release=$(date -u +%Y%m%dT%H%M%S)-$$
work=/root/crm-industry-deploy-$release
mkdir -m 700 "$work" "$work/backup" "$work/build"
old_tag=$(docker inspect --format '{{.Config.Image}}' crm-kimtele-backend)
old_id=$(docker inspect --format '{{.Image}}' crm-kimtele-backend)
[[ "$old_tag" != *@* && "$old_tag" != sha256:* ]]
"${compose[@]}" config --format json > "$work/compose-before.json"
python3 - "$work/compose-before.json" "$work/image.override.json" "$old_tag" <<'IMAGE_OVERRIDE'
import json, sys
config, output, running = sys.argv[1:]
configured = json.load(open(config))["services"]["backend"].get("image")
if configured and configured != running:
    sys.exit("Image Compose berbeda dari container aktif; deploy dihentikan.")
with open(output, "w") as stream:
    json.dump({"services": {"backend": {"image": running}}}, stream)
IMAGE_OVERRIDE
# Build-only Compose services omit image; use the running tag explicitly for apply and rollback.
compose+=(-f "$work/image.override.json")
config_before=$(sha256sum docker-compose.yml docker-compose.override.yml .env .env.ems-v2)
printf '%s\n' "$old_id" > "$work/backup/old-image-id"
printf '%s\n' "$old_tag" > "$work/backup/old-image-tag"
git rev-parse HEAD > "$work/backup/old-head"
git archive --format=tar --output="$work/backup/source-before.tar" HEAD
git diff HEAD > "$work/backup/local-changes.patch"
tar -czf "$work/backup/config-before.tar.gz" .env .env.ems-v2 docker-compose.yml docker-compose.override.yml
docker exec crm-kimtele-postgres sh -eu -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$work/backup/database.dump"
docker exec -i crm-kimtele-postgres pg_restore --file=/dev/null < "$work/backup/database.dump"
tar -C "$work/backup" -czf "$work/backup.tar.gz" .
sha256sum "$work/backup.tar.gz" | tee "$work/backup.sha256"
git archive "$target" | tar -xf - -C "$work/build"
git -c core.hooksPath=/dev/null pull --ff-only "$url" "$target"
test "$(git rev-parse HEAD)" = "$target"
new_tag=crm-industry-backend:$release
printf 'Build backend; layanan lama tetap berjalan. Backup: %s/backup.tar.gz\n' "$work"
docker build -t "$new_tag" "$work/build"
new_id=$(docker image inspect --format '{{.Id}}' "$new_tag")
test "$(sha256sum docker-compose.yml docker-compose.override.yml .env .env.ems-v2)" = "$config_before"
test "$(docker inspect --format '{{.Image}}' crm-kimtele-backend)" = "$old_id"
health() {
  for attempt in {1..60}; do
    [ "$(curl --silent --max-time 3 --output /dev/null --write-out '%{http_code}' https://crm-api.kimcommunication.id/api/auth/me || true)" = 401 ] && return 0
    sleep 2
  done
  return 1
}
complete=0
rollback() {
  result=$?
  trap - EXIT
  if [ "$complete" != 1 ]; then
    set +e
    printf 'Deploy gagal; memulihkan image backend sebelumnya.\n' >&2
    if docker image tag "$old_id" "$old_tag" && "${compose[@]}" up -d --no-deps --no-build --pull never --force-recreate backend && test "$(docker inspect --format '{{.Image}}' crm-kimtele-backend)" = "$old_id" && health; then
      printf 'Image lama kembali aktif; source Git tetap pada commit baru. Database tidak direstore.\n' >&2
    else
      printf 'Rollback perlu pemeriksaan operator. Backup: %s/backup.tar.gz\n' "$work" >&2
    fi
  fi
  exit "$result"
}
trap rollback EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
docker image tag "$new_id" "$old_tag"
"${compose[@]}" up -d --no-deps --no-build --pull never --force-recreate backend
test "$(docker inspect --format '{{.Image}}' crm-kimtele-backend)" = "$new_id"
health
complete=1
printf 'Update industry EMS selesai. Backup privat: %s/backup.tar.gz\n' "$work"
