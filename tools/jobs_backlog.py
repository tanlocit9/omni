"""One-shot read-only local execution/outbox and Kafka backlog inventory."""

import argparse
import json
import subprocess
from datetime import datetime, timezone


def positive(value):
    number = int(value)
    if number < 1 or number > 10000:
        raise argparse.ArgumentTypeError("Expected an integer between 1 and 10000")
    return number


def database_sql(minutes, limit):
    # Only validated integers are interpolated; no user-provided SQL or payload casts.
    return f"""
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='15s';
SET LOCAL lock_timeout='500ms';
WITH unfinished AS (
 SELECT h.id,h.parent_log_id,h.status,h.triggered_at,h.started_at,d.job_type,
  extract(epoch FROM (transaction_timestamp()-h.triggered_at))/60 AS age_minutes
 FROM job_execution_histories h LEFT JOIN job_definitions d ON d.id=h.job_id
 WHERE h.status IN ('PENDING','RUNNING')
), grouped AS (
 SELECT job_type,status,CASE WHEN parent_log_id IS NULL THEN 'root' ELSE 'child' END AS role,
  count(*) AS count,count(*) FILTER (WHERE age_minutes >= {minutes}) AS stale_count,
  min(triggered_at) AS oldest_triggered_at,max(age_minutes) AS oldest_age_minutes
 FROM unfinished GROUP BY job_type,status,(parent_log_id IS NULL)
), sample AS (
 SELECT id,parent_log_id,job_type,status,triggered_at,started_at,age_minutes
 FROM unfinished ORDER BY triggered_at NULLS FIRST,id LIMIT {limit}
), outbox AS (
 SELECT o.status,o.topic,count(*) AS count,min(o.created_at) AS oldest_created_at,
  count(*) FILTER (WHERE o.status='PUBLISHED' AND h.status IN ('PENDING','RUNNING'))
    AS published_unfinished_execution_count
 FROM scheduler_outbox_messages o LEFT JOIN job_execution_histories h ON h.id=o.execution_id
 GROUP BY o.status,o.topic
)
SELECT jsonb_build_object(
 'observedAt',transaction_timestamp(),'database',current_database(),
 'unfinishedCount',(SELECT count(*) FROM unfinished),
 'staleCount',(SELECT count(*) FROM unfinished WHERE age_minutes >= {minutes}),
 'unknownAgeCount',(SELECT count(*) FROM unfinished WHERE triggered_at IS NULL),
 'groups',coalesce((SELECT jsonb_agg(to_jsonb(g) ORDER BY job_type,status,role) FROM grouped g),'[]'::jsonb),
 'oldestExecutions',coalesce((SELECT jsonb_agg(to_jsonb(s) ORDER BY triggered_at NULLS FIRST,id) FROM sample s),'[]'::jsonb),
 'sampleTruncated',(SELECT count(*) > {limit} FROM unfinished),
 'outbox',coalesce((SELECT jsonb_agg(to_jsonb(o) ORDER BY status,topic) FROM outbox o),'[]'::jsonb),
 'atomicSchemaPresent',to_regclass('public.atomic_work_attempts') IS NOT NULL
);
ROLLBACK;
"""


def command(args, stdin=None):
    result = subprocess.run(
        args, input=stdin, text=True, encoding="utf-8", errors="replace",
        capture_output=True, timeout=30,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if result.returncode:
        raise RuntimeError((result.stderr.strip() or result.stdout.strip())[-1000:])
    return result.stdout, result.stderr


def read_database(args):
    output, _ = command([
        "docker", "exec", "-i", args.postgres_container, "psql", "-X", "-qAt",
        "-v", "ON_ERROR_STOP=1", "-U", args.db_user, "-d", args.database,
    ], database_sql(args.stale_minutes, args.limit))
    result = json.loads(output.strip())
    if not isinstance(result, dict) or "unfinishedCount" not in result:
        raise ValueError("Invalid PostgreSQL diagnostic response")
    return result


def parse_kafka(stdout, stderr=""):
    rows = []
    inactive = []
    for line in (stdout + "\n" + stderr).splitlines():
        line = line.strip()
        if not line or line.startswith("GROUP "):
            continue
        if "has no active members" in line:
            parts = line.split("'", 2)
            if len(parts) != 3:
                raise ValueError("Unrecognized inactive consumer-group output")
            inactive.append(parts[1])
            continue
        if line.startswith("Consumer group ") and "has no offsets" in line:
            raise ValueError("Consumer group exists without readable offsets")
        fields = line.split()
        if len(fields) >= 9 and fields[2].isdigit():
            def offset(value):
                number = None if value == "-" else int(value)
                return number if number is not None and number >= 0 else None

            rows.append({
                "group": fields[0], "topic": fields[1], "partition": int(fields[2]),
                "committedOffset": offset(fields[3]), "logEndOffset": offset(fields[4]),
                "lag": offset(fields[5]), "hasActiveConsumer": fields[6] != "-",
            })
        elif "ERROR" in line.upper() or "EXCEPTION" in line.upper():
            raise ValueError("Kafka returned an error while describing offsets")
        elif line.startswith("Warning:") or line.startswith("[20"):
            # JVM warnings do not invalidate successfully parsed rows.
            continue
        else:
            raise ValueError("Unrecognized Kafka consumer-group output")
    if not rows:
        raise ValueError("No readable consumer partition offsets; backlog is unknown")
    return {
        "observedAt": datetime.now(timezone.utc).isoformat(),
        "knownLag": sum(row["lag"] for row in rows if row["lag"] is not None),
        "unknownLagPartitions": sum(row["lag"] is None for row in rows),
        "inactiveGroups": sorted(set(inactive)), "partitions": rows,
    }


def read_kafka(args):
    stdout, stderr = command([
        "docker", "exec", args.kafka_container, "kafka-consumer-groups",
        "--bootstrap-server", args.bootstrap_server, "--describe", "--all-groups", "--offsets",
    ])
    return parse_kafka(stdout, stderr)


def collect(args):
    report = {"version": 1, "staleMinutes": args.stale_minutes,
              "note": "Execution age is since trigger, not a heartbeat. Kafka lag is committed-offset backlog; DB and Kafka are separate observations. Atomic ownership, dataset readiness and provider health are not checked."}
    for name, reader, skip in [("database", read_database, args.skip_db),
                               ("kafka", read_kafka, args.skip_kafka)]:
        if skip:
            report[name] = {"status": "not_checked"}
            continue
        try:
            report[name] = {"status": "observed", **reader(args)}
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
            report[name] = {"status": "unavailable", "error": str(error)}
    report["complete"] = all(report[name]["status"] == "observed" for name in ("database", "kafka"))
    return report


def render(report):
    lines = [f"Job backlog (review threshold: {report['staleMinutes']} minutes)"]
    db = report["database"]
    if db["status"] == "observed":
        lines.append(f"DB: {db['unfinishedCount']} unfinished; {db['staleCount']} past threshold; {db['unknownAgeCount']} unknown age")
        lines.append("JOB TYPE | STATUS | ROLE | COUNT | STALE | OLDEST AGE (min)")
        for group in db["groups"]:
            age = group["oldest_age_minutes"]
            lines.append(f"{group['job_type']} | {group['status']} | {group['role']} | {group['count']} | {group['stale_count']} | {round(age, 1) if age is not None else 'unknown'}")
        lines.append(f"Oldest execution sample: {len(db['oldestExecutions'])}; truncated={db['sampleTruncated']} (details via --json)")
        for row in db["outbox"]:
            if row["status"] != "PUBLISHED" or row["published_unfinished_execution_count"]:
                lines.append(f"Outbox {row['topic']} {row['status']}: {row['count']}; published/nonterminal={row['published_unfinished_execution_count']}")
    else:
        lines.append(f"DB: {db['status']} {db.get('error', '')}")
    kafka = report["kafka"]
    if kafka["status"] == "observed":
        lines.append(f"Kafka: known lag={kafka['knownLag']}; unknown partitions={kafka['unknownLagPartitions']}")
        lines.append("GROUP | TOPIC | PARTITION | LAG | ACTIVE")
        for row in kafka["partitions"]:
            lines.append(f"{row['group']} | {row['topic']} | {row['partition']} | {row['lag']} | {row['hasActiveConsumer']}")
    else:
        lines.append(f"Kafka: {kafka['status']} {kafka.get('error', '')}")
    lines.append(report["note"])
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stale-minutes", type=positive, default=30)
    parser.add_argument("--limit", type=positive, default=20)
    parser.add_argument("--postgres-container", default="postgres")
    parser.add_argument("--database", default="omni")
    parser.add_argument("--db-user", default="postgres")
    parser.add_argument("--kafka-container", default="local-kafka")
    parser.add_argument("--bootstrap-server", default="localhost:9092")
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--skip-kafka", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    report = collect(args)
    print(json.dumps(report, indent=2) if args.json else render(report))
    return 2 if any(report[name]["status"] == "unavailable" for name in ("database", "kafka")) else 0


if __name__ == "__main__":
    raise SystemExit(main())
