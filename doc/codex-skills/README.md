# Codex Skills

Repository-provided Codex skills live in `doc/codex-skills/SKIL_*` and can be installed with:

```sh
make install-codex-skills
```

Show all sample prompts with:

```sh
make codex-skill-prompts
```

## Available Skills

| Skill | Description | Prompt |
| --- | --- | --- |
| `check-env-config` | Compares `.env`, `.env.sample`, and repository references to find missing, stale, or unused environment variables. | `Use the check-env-config skill to verify .env and .env.sample in this repository.` |
