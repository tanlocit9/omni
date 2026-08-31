# Manual Verification Handoff

Use `tools/check_result.py` when an owner wants to run lint, tests, or builds manually
and let an agent consume only a compact conclusion. Artifacts and raw logs are stored
under the ignored `.agent/check-results/<increment>/` directory.

## Define required checks

Define the complete gate before running checks. A passing unlisted check cannot make
an increment pass.

```powershell
python tools/check_result.py init --increment P3-I5 --require test/analyzer-tests --require lint/analyzer-lint
```

Supported kinds are `test`, `lint`, `build`, `format`, `integration`, and `other`.
Check names and increment IDs may contain letters, numbers, dots, underscores, and
hyphens.

## Wrap a manually selected command

The owner chooses and invokes every command. The tool streams output, saves a raw
log, records the exit code, and returns the same exit code.

```powershell
python tools/check_result.py run --increment P3-I5 --kind test --name analyzer-tests -- nx run analyzer:test
python tools/check_result.py run --increment P3-I5 --kind lint --name analyzer-lint -- nx run analyzer:lint
```

Exit-code evidence is authoritative. The tool does not select checks or run commands
unless explicitly invoked with `run`.

## Import an existing log

Save logs inside the increment's log directory:

```powershell
New-Item -ItemType Directory -Force .agent/check-results/P3-I5/logs | Out-Null
nx run analyzer:test *> .agent/check-results/P3-I5/logs/analyzer-tests.log
$code = $LASTEXITCODE
python tools/check_result.py import-log --increment P3-I5 --kind test --name analyzer-tests --format nx --exit-code $code --log .agent/check-results/P3-I5/logs/analyzer-tests.log
```

When an exit code is unavailable, omit `--exit-code`. The fallback parser supports
`nx`, `pytest`, and `eslint`. Parsing is conservative: an empty, truncated,
contradictory, or unrecognized log is `unknown`, never a pass. The `generic` format
requires an exit code.

## Produce the conclusion

```powershell
python tools/check_result.py summarize --increment P3-I5
python tools/check_result.py conclusion --increment P3-I5
```

The agent normally runs only `conclusion`, which rebuilds `summary.json` and emits
one line:

```text
PASS P3-I5 required=2 pass=2 fail=0 unknown=0 missing=0 sources=exit_code
```

Conclusion rules:

- `PASS`: every required check has a passing latest result.
- `FAIL`: at least one required check has a failing latest result.
- `INCOMPLETE`: at least one required check is missing or unknown, with no failure.
- `INVALID`: the manifest, records, names, paths, or files are invalid.

Exit codes are `0` for pass, `2` for incomplete, `3` for failure, and `4` for invalid
input. Prior attempts remain in `runs.jsonl`; the newest record for each required
check determines the conclusion. If a recorded log is changed or removed, its result
becomes unknown.

Raw logs should be inspected only for explicit failure diagnosis. Roadmap evidence
must describe a passing result as owner-supplied rather than agent-verified.
