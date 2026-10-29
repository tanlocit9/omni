"""Execution input bindings are context, separate from atomic work identity."""

from typing import Any


def approved_input_version(
    metadata: dict[str, Any], dataset: str, partition: dict[str, str], *, required: bool
) -> str | None:
    bindings = metadata.get("approvedInputs")
    if bindings is None and not required:
        return None
    if not isinstance(bindings, list):
        raise ValueError("Missing or invalid approved input binding")
    matches = []
    for binding in bindings:
        if not isinstance(binding, dict):
            raise ValueError("Invalid approved input binding entry")
        if binding.get("dataset") == dataset and binding.get("partition") == partition:
            version = binding.get("dataVersion")
            if not isinstance(version, str) or not version:
                raise ValueError("Invalid approved input version")
            matches.append(version)
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous approved input version")
    return matches[0]
