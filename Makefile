.PHONY: help docker-up docker-app-up docker-down docker-data-permissions docker-pg-backup docker-pg-history-stats install-pg-backup-cron ensure-pg-backup-cron docker-pg-shell docker-logs docker-app-logs app-health build run run-local run-prod test clean bump-patch bump-minor codex-commit install-codex-skills codex-skill-prompts

-include .env

CODEX_HOME ?= $(HOME)/.codex
PROFILE ?= $(SPRING_PROFILES_ACTIVE)
PROFILE ?= local
POSTGRES_PORT ?= 5433
APP_PORT ?= 8080
LOCAL_UID ?= $(shell id -u)
LOCAL_GID ?= $(shell id -g)
GRADLE_USER_HOME ?= /tmp/home-assistant-watchdog-gradle-home
TEST_GRADLE_ARGS ?=
TEST_GRADLE_WORKERS ?=
TEST_GRADLE_JVMARGS ?=
CRON_SCHEDULE ?= 0 2 * * *
CRON_MAKE ?= $(shell command -v make 2>/dev/null || echo make)
PG_BACKUP_CRON_MARKER ?= home-assistant-watchdog-docker-pg-backup
export GRADLE_USER_HOME

help:
	@printf "Home Assistant Watchdog - Makefile targets\n"
	@printf "\n"
	@printf "  PostgreSQL port from .env: $(POSTGRES_PORT)\n"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Docker"
	@printf "    %-28s %s\n" "docker-up" "Start local infrastructure from compose.yaml"
	@printf "    %-28s %s\n" "docker-app-up" "Build/start PostgreSQL and the Spring Boot container"
	@printf "    %-28s %s\n" "docker-down" "Stop and remove local infrastructure containers"
	@printf "    %-28s %s\n" "docker-logs" "Show compose logs in follow mode"
	@printf "    %-28s %s\n" "docker-app-logs" "Show application logs in follow mode"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "PostgreSQL in Docker"
	@printf "    %-28s %s\n" "docker-data-permissions" "Prepare docker-data directories and permissions"
	@printf "    %-28s %s\n" "docker-pg-backup" "Dump PostgreSQL and zip it into docker-data/backup/postgres"
	@printf "    %-28s %s\n" "docker-pg-history-stats" "Show parameter-history row counts and oldest/newest timestamps"
	@printf "    %-28s %s\n" "install-pg-backup-cron" "Install daily PostgreSQL backup cron job"
	@printf "    %-28s %s\n" "ensure-pg-backup-cron" "Install daily PostgreSQL backup cron job only when missing"
	@printf "    %-28s %s\n" "docker-pg-shell" "Open PostgreSQL shell inside the compose postgres container"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Application"
	@printf "    %-28s %s\n" "build" "Build the project, skipping tests"
	@printf "    %-28s %s\n" "run" "Run Spring Boot with SPRING_PROFILES_ACTIVE from .env"
	@printf "    %-28s %s\n" "run-local" "Run Spring Boot with local profile"
	@printf "    %-28s %s\n" "run-prod" "Run Spring Boot with prod profile"
	@printf "    %-28s %s\n" "app-health" "Check the configured local app health endpoint"
	@printf "    %-28s %s\n" "test" "Run all tests"
	@printf "    %-28s %s\n" "clean" "Clean Gradle build artifacts"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Repository"
	@printf "    %-28s %s\n" "bump-patch" "Auto-increment patch for x.y.z-SNAPSHOT versions"
	@printf "    %-28s %s\n" "bump-minor" "Auto-increment minor and reset patch for x.y.z-SNAPSHOT versions"
	@printf "    %-28s %s\n" "codex-commit" "Bump version if needed, stage, commit with Codex, and optionally push"
	@printf "    %-28s %s\n" "install-codex-skills" "Install all project Codex skills into CODEX_HOME"
	@printf "    %-28s %s\n" "codex-skill-prompts" "Show sample prompts for project Codex skills"
	@printf "\n"

docker-up: docker-data-permissions
	docker compose -f compose.yaml up -d postgres
	@echo ""
	@echo "Services started:"
	@echo "  - PostgreSQL: localhost:$(POSTGRES_PORT)"
	@echo "  - App:        http://localhost:$(APP_PORT) once Spring Boot is running"
	@echo ""

docker-app-up: docker-data-permissions
	docker compose -f compose.yaml up -d --build postgres springboot
	@echo ""
	@echo "Application started:"
	@echo "  - App:        http://localhost:$(APP_PORT)"
	@echo "  - Health:     http://localhost:$(APP_PORT)/actuator/health"
	@echo "  - PostgreSQL: localhost:$(POSTGRES_PORT)"
	@echo ""

docker-down:
	docker compose -f compose.yaml down

docker-logs:
	docker compose -f compose.yaml logs -f

docker-app-logs:
	docker compose -f compose.yaml logs -f springboot

docker-data-permissions:
	@mkdir -p ./docker-data/backup/postgres ./docker-data/postgres ./docker-data/data ./logs
	@chmod -R a+rwX ./logs
	@docker run --rm -v "$(PWD)/docker-data:/work" alpine:3.20 \
		sh -c "mkdir -p /work/postgres /work/data /work/backup/postgres && chown -R $(LOCAL_UID):$(LOCAL_GID) /work/backup && chmod 755 /work /work/postgres /work/data && chmod -R u+rwX,go-rwx /work/backup"

docker-pg-backup: docker-data-permissions
	@mkdir -p ./docker-data/backup/postgres
	@timestamp=$$(date +"%Y-%m-%d_%H-%M-%S"); \
	base="home-assistant-watchdog-postgres-$$timestamp"; \
	sql_path="./docker-data/backup/postgres/$$base.sql"; \
	zip_path="./docker-data/backup/postgres/$$base.zip"; \
	echo "Dumping PostgreSQL database '$(POSTGRES_DB)' to $$sql_path ..."; \
	docker compose -f compose.yaml exec -T postgres pg_dump -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)" --clean --if-exists > "$$sql_path"; \
	zip -j "$$zip_path" "$$sql_path" >/dev/null; \
	rm "$$sql_path"; \
	echo "Backup written to $$zip_path"

docker-pg-history-stats:
	docker compose -f compose.yaml exec -T postgres psql -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)" \
		-c "SELECT count(*) AS rows, min(observed_at) AS oldest_observed_at, max(observed_at) AS newest_observed_at FROM device_parameter_history;"

install-pg-backup-cron: docker-data-permissions
	@job='$(CRON_SCHEDULE) cd $(PWD) && $(CRON_MAKE) docker-pg-backup >> $(PWD)/docker-data/backup/postgres/cron.log 2>&1 # $(PG_BACKUP_CRON_MARKER)'; \
	current=$$(crontab -l 2>/dev/null || true); \
	current=$$(printf '%s\n' "$$current" | grep -Fv "$(PG_BACKUP_CRON_MARKER)" || true); \
	( printf '%s\n' "$$current"; printf '%s\n' "$$job" ) | sed '/^$$/d' | crontab -
	@echo "Installed daily PostgreSQL backup cron job: $(CRON_SCHEDULE)"

ensure-pg-backup-cron:
	@current=$$(crontab -l 2>/dev/null || true); \
	if printf '%s\n' "$$current" | grep -Fq "$(PG_BACKUP_CRON_MARKER)"; then \
		echo "PostgreSQL backup cron job already installed"; \
		exit 0; \
	fi; \
	$(MAKE) install-pg-backup-cron

docker-pg-shell:
	docker compose -f compose.yaml exec postgres psql -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)"

build:
	./gradlew build -x test

run:
	SPRING_PROFILES_ACTIVE=$(PROFILE) ./gradlew bootRun

run-local:
	SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

run-prod:
	SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun

app-health:
	curl -fsS "http://localhost:$(APP_PORT)/actuator/health"

test:
	./gradlew $(if $(TEST_GRADLE_WORKERS),--max-workers=$(TEST_GRADLE_WORKERS),) $(if $(TEST_GRADLE_JVMARGS),-Dorg.gradle.jvmargs="$(TEST_GRADLE_JVMARGS)",) test $(TEST_GRADLE_ARGS)

clean:
	./gradlew clean

bump-patch:
	@current=$$(perl -ne 'print $$1 if /^version\s*=\s*"([^"]+)"/' build.gradle.kts); \
	if echo "$$current" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$$'; then \
		major=$$(echo "$$current" | cut -d. -f1); \
		minor=$$(echo "$$current" | cut -d. -f2); \
		patch=$$(echo "$$current" | sed -E 's/^[0-9]+\.[0-9]+\.([0-9]+)-SNAPSHOT$$/\1/'); \
		next_patch=$$((patch + 1)); \
		next="$$major.$$minor.$$next_patch-SNAPSHOT"; \
		perl -i -pe "s/^version\s*=\s*\"[^\"]+\"/version = \"$$next\"/" build.gradle.kts; \
		echo "Version set to $$next"; \
	else \
		echo "Current version '$$current' is not x.y.z-SNAPSHOT; cannot auto-bump patch"; \
		exit 1; \
	fi

bump-minor:
	@current=$$(perl -ne 'print $$1 if /^version\s*=\s*"([^"]+)"/' build.gradle.kts); \
	if echo "$$current" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$$'; then \
		major=$$(echo "$$current" | cut -d. -f1); \
		minor=$$(echo "$$current" | cut -d. -f2); \
		next_minor=$$((minor + 1)); \
		next="$$major.$$next_minor.0-SNAPSHOT"; \
		perl -i -pe "s/^version\s*=\s*\"[^\"]+\"/version = \"$$next\"/" build.gradle.kts; \
		echo "Version set to $$next"; \
	else \
		echo "Current version '$$current' is not x.y.z-SNAPSHOT; cannot auto-bump minor"; \
		exit 1; \
	fi

codex-commit:
	utilities/codex-commit.sh

# Install repository-provided Codex skills into the local Codex home.
install-codex-skills:
	@mkdir -p "$(CODEX_HOME)/skills"
	@count=0; \
	for skill in doc/codex-skills/SKIL_*; do \
		if [ -d "$$skill" ]; then \
			name=$$(basename "$$skill"); \
			rm -rf "$(CODEX_HOME)/skills/$$name"; \
			cp -R "$$skill" "$(CODEX_HOME)/skills/"; \
			count=$$((count + 1)); \
		fi; \
	done; \
	if [ "$$count" -eq 0 ]; then \
		echo "No Codex skills found in doc/codex-skills/SKIL_*"; \
		exit 1; \
	fi
	@echo "✓ Codex skills installed to $(CODEX_HOME)/skills"

# Show sample prompts for repository-provided Codex skills.
codex-skill-prompts:
	@printf "Codex skill sample prompts\n\n"
	@count=0; \
	missing=0; \
	for skill in doc/codex-skills/SKIL_*; do \
		if [ -d "$$skill" ]; then \
			name=$$(basename "$$skill" | sed 's/^SKIL_//'); \
			prompt="$$skill/prompt.txt"; \
			printf '\033[1;94m%s\033[0m\n' "$$name"; \
			if [ -f "$$prompt" ]; then \
				sed 's/^/  /' "$$prompt"; \
			else \
				echo "  Missing $$prompt"; \
				missing=$$((missing + 1)); \
			fi; \
			printf '\n'; \
			count=$$((count + 1)); \
		fi; \
	done; \
	if [ "$$count" -eq 0 ]; then \
		echo "No Codex skills found in doc/codex-skills/SKIL_*"; \
		exit 1; \
	fi; \
	if [ "$$missing" -gt 0 ]; then \
		exit 1; \
	fi
