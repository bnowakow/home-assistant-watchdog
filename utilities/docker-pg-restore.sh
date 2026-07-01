#!/usr/bin/env bash
set -euo pipefail

backup_dir=${1:-./docker-data/backup/postgres}
compose_file=${2:-compose.yaml}
postgres_service=${3:-postgres}
postgres_user=${POSTGRES_USER:?POSTGRES_USER is required}
postgres_db=${POSTGRES_DB:?POSTGRES_DB is required}
selected_backup=${BACKUP:-}

normalize_path() {
	printf '%s' "$1" | tr -d '\r\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

require_tty() {
	if [[ ! -t 0 || ! -t 1 ]]; then
		echo "docker-pg-restore requires an interactive terminal." >&2
		echo "Run it from a shell attached to a TTY, or set BACKUP=/path/to/backup.zip for noninteractive restore selection." >&2
		exit 1
	fi
}

choose_backup() {
	local -a backups
	mapfile -t backups < <(
		find "$backup_dir" -maxdepth 1 -type f -name '*.zip' -printf '%T@ %p\n' |
			sort -rn |
			sed 's/^[^ ]* //'
	)

	if [[ ${#backups[@]} -eq 0 ]]; then
		echo "No PostgreSQL backup zip files found in $backup_dir" >&2
		return 1
	fi

	local picker
	if command -v dialog >/dev/null 2>&1; then
		picker=dialog
	elif command -v whiptail >/dev/null 2>&1; then
		picker=whiptail
	else
		echo "dialog or whiptail is required for interactive restore selection." >&2
		echo "Install one of them or run with BACKUP=/path/to/backup.zip make docker-pg-restore" >&2
		return 1
	fi

		local -a menu_items
	local backup basename size modified
	for backup in "${backups[@]}"; do
		basename=$(basename "$backup")
		size=$(du -h "$backup" | awk '{print $1}')
		modified=$(stat -c '%y' "$backup" | cut -d. -f1)
		menu_items+=("$backup" "$basename  $size  $modified")
	done

	if [[ "$picker" == dialog ]]; then
		"$picker" --stdout --clear \
			--title "PostgreSQL backup restore" \
			--menu "Choose a backup to restore into ${postgres_db}. This will replace data in the current database." \
			22 110 14 \
			"${menu_items[@]}"
	else
		"$picker" --output-fd 1 --title "PostgreSQL backup restore" \
			--menu "Choose a backup to restore into ${postgres_db}. This will replace data in the current database." \
			22 110 14 \
			"${menu_items[@]}"
	fi
}

require_tty

if [[ -z "$selected_backup" ]]; then
	selected_backup=$(choose_backup)
fi

selected_backup=$(normalize_path "$selected_backup")

if [[ ! -f "$selected_backup" ]]; then
	echo "Backup file does not exist: $selected_backup" >&2
	exit 1
fi

case "$selected_backup" in
	*.zip) ;;
	*)
		echo "Backup must be a .zip file: $selected_backup" >&2
		exit 1
		;;
esac

if ! unzip -l "$selected_backup" '*.sql' >/dev/null 2>&1; then
	echo "Backup zip does not contain a SQL dump: $selected_backup" >&2
	exit 1
fi

echo "Selected backup: $selected_backup"

read -r -p "Restore into PostgreSQL database '${postgres_db}' from '$selected_backup'? This replaces current data. Type RESTORE to continue: " confirmation </dev/tty
if [[ "$confirmation" != "RESTORE" ]]; then
	echo "Restore cancelled."
	exit 1
fi

echo "Restoring PostgreSQL database '${postgres_db}' from $selected_backup ..."
unzip -p "$selected_backup" '*.sql' |
	docker compose -f "$compose_file" exec -T "$postgres_service" psql \
		-v ON_ERROR_STOP=1 \
		-U "$postgres_user" \
		-d "$postgres_db"
echo "Restore completed."
