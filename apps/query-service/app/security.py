from __future__ import annotations

from dataclasses import dataclass

from sqlglot import exp, parse
from sqlglot.errors import ParseError


class SqlRejectedError(ValueError):
    """Raised when SQL exceeds the read-only query boundary."""


@dataclass(frozen=True)
class ValidatedSql:
    sql: str
    root_kind: str


_ALLOWED_ROOTS = {"select", "union", "describe", "explain", "show"}
_FORBIDDEN_NODE_KEYS = {
    "alter",
    "analyze",
    "attach",
    "command",
    "copy",
    "create",
    "delete",
    "detach",
    "drop",
    "insert",
    "load",
    "merge",
    "pragma",
    "transaction",
    "truncate",
    "update",
    "use",
}
_FORBIDDEN_FUNCTIONS = {
    "glob",
    "http_get",
    "parquet_scan",
    "read_blob",
    "read_csv",
    "read_csv_auto",
    "read_json",
    "read_json_auto",
    "read_ndjson",
    "read_parquet",
    "sqlite_scan",
}


def validate_read_only_sql(sql: str, allowed_tables: set[str]) -> ValidatedSql:
    """Parse SQL and allow only read-only operations over registered views."""
    try:
        statements = parse(sql, read="duckdb")
    except ParseError as exc:
        raise SqlRejectedError("SQL could not be parsed") from exc
    if len(statements) != 1:
        raise SqlRejectedError("Exactly one SQL statement is required")

    statement = statements[0]
    root_kind = statement.key.lower()
    if root_kind not in _ALLOWED_ROOTS:
        raise SqlRejectedError(f"Statement type {root_kind!r} is not allowed")

    for node in statement.walk():
        if node.key.lower() in _FORBIDDEN_NODE_KEYS:
            raise SqlRejectedError(f"SQL operation {node.key.lower()!r} is not allowed")
        if isinstance(node, exp.Func):
            function_name = node.sql_name().lower()
            if function_name in _FORBIDDEN_FUNCTIONS or function_name.startswith(
                ("read_", "http_", "pragma_")
            ):
                raise SqlRejectedError(f"SQL function {function_name!r} is not allowed")

    referenced_tables = {
        table.name.lower() for table in statement.find_all(exp.Table) if table.name
    }
    cte_names = {
        cte.alias_or_name.lower()
        for cte in statement.find_all(exp.CTE)
        if cte.alias_or_name
    }
    unauthorized = (
        referenced_tables - cte_names - {item.lower() for item in allowed_tables}
    )
    if unauthorized:
        raise SqlRejectedError(
            "SQL references undeclared logical views: "
            + ", ".join(sorted(unauthorized))
        )

    return ValidatedSql(
        sql=statement.sql(dialect="duckdb"),
        root_kind=root_kind,
    )
