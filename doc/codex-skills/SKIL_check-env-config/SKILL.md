---
name: check-env-config
description: Audit repository environment variable drift. Use when Codex needs to compare .env against .env.sample, verify that .env contains only variables declared in the sample or otherwise intentionally allowed, verify that .env.sample contains every variable from .env and every variable referenced by application/config/deployment files, identify variables used by the repository but missing from .env.sample, and flag stale or unused environment variables in local configuration.
---

# Check Env Config

## Workflow

1. Run `scripts/check_env_config.py` from this skill against the repository root.
2. Review the report before editing files. Treat findings as evidence, not automatic fixes.
3. If the user asked for fixes, update `.env`, `.env.sample`, docs, or configuration names to match the codebase's current convention. Keep `.env` and `.env.sample` line-comparable: use the same section order, put matching variable names on matching lines where practical, remove stale variables from both files, and add newly required variables next to related settings. Preserve existing local `.env` values and do not expose secret values from `.env` in the final answer.
4. If code or docs changed, run the repository's required validation before finishing.

## Quick Start

```bash
python3 /Users/sup/.codex/skills/SKIL_check-env-config/scripts/check_env_config.py /path/to/repo
```

Useful options:

```bash
python3 /Users/sup/.codex/skills/SKIL_check-env-config/scripts/check_env_config.py /path/to/repo --json
python3 /Users/sup/.codex/skills/SKIL_check-env-config/scripts/check_env_config.py /path/to/repo --env .env.local --sample .env.example
```

## Interpretation

- `missing_from_env`: variables in the sample that are absent from `.env`.
- `extra_in_env`: variables present in `.env` but absent from the sample.
- `used_but_not_sampled`: variables referenced by repository files but absent from the sample.
- `missing_from_sample`: variables present in `.env` or referenced by repository files but absent from the sample.
- `sampled_but_not_used`: variables declared in the sample but not found in repository references.

For Spring Boot projects, variables may be used inside Spring placeholder expressions, Docker Compose interpolation, shell scripts, docs, or tests. Prefer the application's actual binding files, deployment files, and sample env files over stale docs when resolving naming conflicts.

The bundled script intentionally skips generated frontend assets, build outputs, repository skill bundles, VCS metadata, and Gradle wrapper internals by default. If a finding still looks suspicious, inspect `used_references` and classify it as a false positive before changing configuration.

## Safety

Never print `.env` values. Report variable names only. Preserve comments and ordering unless the user asks for normalization. When normalizing, keep `.env` and `.env.sample` aligned so reviewers can compare them by line.
