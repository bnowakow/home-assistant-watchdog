#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path


DEFAULT_EXCLUDES = {
    ".git",
    ".gradle",
    ".idea",
    ".mvn",
    ".venv",
    "build",
    "codex-skills",
    "generated",
    "node_modules",
    "target",
}
DEFAULT_EXCLUDED_FILES = {
    "gradlew",
    "gradlew.bat",
}
DEFAULT_IGNORED_NAMES = {
    "CDPATH",
    "DEFAULT_JVM_OPTS",
    "GRADLE_OPTS",
    "HOME",
    "JAVACMD",
    "JAVA_HOME",
    "JAVA_OPTS",
    "MAX_FD",
    "PWD",
    "TERM",
    "TMPDIR",
}

ENV_KEY_RE = re.compile(r"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=")
ENV_REF_PATTERNS = [
    re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?\}"),
    re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)"),
]


def parse_env_keys(path: Path) -> list[str]:
    if not path.exists():
        return []
    keys = []
    seen = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = ENV_KEY_RE.match(line)
        if match and match.group(1) not in seen:
            key = match.group(1)
            keys.append(key)
            seen.add(key)
    return keys


def should_skip(path: Path, root: Path, ignored_files: set[str]) -> bool:
    rel = path.relative_to(root)
    return path.name in ignored_files or any(part in DEFAULT_EXCLUDES for part in rel.parts)


def iter_text_files(root: Path, env_path: Path, sample_path: Path, ignored_files: set[str]) -> list[Path]:
    files = []
    for path in root.rglob("*"):
        if not path.is_file() or should_skip(path, root, ignored_files) or path in {env_path, sample_path}:
            continue
        try:
            chunk = path.read_bytes()[:4096]
        except OSError:
            continue
        if b"\0" in chunk:
            continue
        files.append(path)
    return files


def discover_used_vars(
    root: Path,
    env_path: Path,
    sample_path: Path,
    ignored_names: set[str],
    ignored_files: set[str],
) -> dict[str, list[str]]:
    refs: dict[str, set[str]] = {}
    for path in iter_text_files(root, env_path, sample_path, ignored_files):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        rel = str(path.relative_to(root))
        for pattern in ENV_REF_PATTERNS:
            for match in pattern.finditer(text):
                name = match.group(1)
                if name.isupper() and len(name) > 1 and name not in ignored_names:
                    refs.setdefault(name, set()).add(rel)
    return {name: sorted(paths) for name, paths in sorted(refs.items())}


def make_report(
    root: Path,
    env_name: str,
    sample_name: str,
    ignored_names: set[str],
    ignored_files: set[str],
) -> dict:
    env_path = root / env_name
    sample_path = root / sample_name
    env_keys = parse_env_keys(env_path)
    sample_keys = parse_env_keys(sample_path)
    used_refs = discover_used_vars(root, env_path, sample_path, ignored_names, ignored_files)
    env_set = set(env_keys)
    sample_set = set(sample_keys)
    used_set = set(used_refs)
    missing_from_sample = (env_set | used_set) - sample_set
    return {
        "root": str(root),
        "env_file": env_name,
        "sample_file": sample_name,
        "env_exists": env_path.exists(),
        "sample_exists": sample_path.exists(),
        "missing_from_env": sorted(sample_set - env_set),
        "extra_in_env": sorted(env_set - sample_set),
        "used_but_not_sampled": sorted(used_set - sample_set),
        "missing_from_sample": sorted(missing_from_sample),
        "sampled_but_not_used": sorted(sample_set - used_set),
        "used_references": used_refs,
    }


def print_text_report(report: dict) -> None:
    print(f"Repository: {report['root']}")
    print(f"Env file: {report['env_file']} ({'found' if report['env_exists'] else 'missing'})")
    print(f"Sample file: {report['sample_file']} ({'found' if report['sample_exists'] else 'missing'})")
    print()
    for key in [
        "missing_from_env",
        "extra_in_env",
        "used_but_not_sampled",
        "missing_from_sample",
        "sampled_but_not_used",
    ]:
        values = report[key]
        print(f"{key}: {len(values)}")
        for value in values:
            print(f"  - {value}")
        print()
    if report["used_but_not_sampled"]:
        print("References for used_but_not_sampled:")
        for name in report["used_but_not_sampled"]:
            paths = report["used_references"].get(name, [])
            print(f"  - {name}: {', '.join(paths[:8])}")
            if len(paths) > 8:
                print(f"    ... and {len(paths) - 8} more")


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare .env, .env.sample, and repository env references.")
    parser.add_argument("root", nargs="?", default=".", help="Repository root")
    parser.add_argument("--env", default=".env", help="Environment file name")
    parser.add_argument("--sample", default=".env.sample", help="Sample environment file name")
    parser.add_argument("--ignore-name", action="append", default=[], help="Variable name to ignore")
    parser.add_argument("--ignore-file", action="append", default=[], help="File basename to ignore")
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    ignored_names = DEFAULT_IGNORED_NAMES | set(args.ignore_name)
    ignored_files = DEFAULT_EXCLUDED_FILES | set(args.ignore_file)
    report = make_report(root, args.env, args.sample, ignored_names, ignored_files)
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_text_report(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
