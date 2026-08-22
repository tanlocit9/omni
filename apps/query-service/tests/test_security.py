import pytest

from app.security import SqlRejectedError, validate_read_only_sql


def test_allows_select_over_declared_logical_view() -> None:
    validated = validate_read_only_sql(
        "WITH latest AS (SELECT * FROM eod) SELECT code FROM latest",
        {"eod"},
    )

    assert validated.root_kind == "select"


@pytest.mark.parametrize(
    "sql",
    [
        "DELETE FROM eod",
        "COPY eod TO '/tmp/leak.csv'",
        "INSTALL httpfs",
        "SELECT * FROM read_parquet('s3://other/private.parquet')",
        "SELECT * FROM 'https://example.com/data.parquet'",
    ],
)
def test_rejects_side_effects_and_physical_sources(sql: str) -> None:
    with pytest.raises(SqlRejectedError):
        validate_read_only_sql(sql, {"eod"})


def test_rejects_undeclared_table() -> None:
    with pytest.raises(SqlRejectedError, match="undeclared"):
        validate_read_only_sql("SELECT * FROM secrets", {"eod"})
