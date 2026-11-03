"""Execution input bindings are context, separate from atomic work identity."""

from typing import Any
from datetime import date
from uuid import UUID
import re


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
            identity_fields = ("atomicWorkId", "businessDate", "atomicAttemptId")
            if "outputCompletion" in metadata or any(key in binding for key in identity_fields):
                work, day, attempt = (binding.get(key) for key in identity_fields)
                if not isinstance(work, str) or re.fullmatch(r"work:[0-9a-f]{64}", work) is None:
                    raise ValueError("Missing or invalid approved upstream work identity")
                if not isinstance(day, str) or date.fromisoformat(day).isoformat() != day:
                    raise ValueError("Invalid approved upstream business date")
                if not isinstance(attempt, str) or str(UUID(attempt)) != attempt:
                    raise ValueError("Invalid approved upstream attempt identity")
            version = binding.get("dataVersion")
            if not isinstance(version, str) or not version:
                raise ValueError("Invalid approved input version")
            if ("outputCompletion" in metadata or any(key in binding for key in identity_fields)) \
                    and re.fullmatch(r"sha256:[0-9a-f]{64}", version) is None:
                raise ValueError("Invalid approved work output version")
            matches.append(version)
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous approved input version")
    return matches[0]


def approved_input_bindings(metadata):
    bindings = metadata.get("approvedInputs")
    if not isinstance(bindings, list):
        raise ValueError("Missing or invalid approved input binding")
    seen = set()
    for binding in bindings:
        if not isinstance(binding, dict):
            raise ValueError("Invalid approved input binding entry")
        approved_input_version({**metadata, "approvedInputs": [binding]}, binding.get("dataset"), binding.get("partition"), required=True)
        identity = (binding["dataset"], tuple(sorted(binding["partition"].items())), binding["atomicWorkId"])
        if identity in seen:
            raise ValueError("Duplicate approved work input")
        seen.add(identity)
    return bindings


def approved_manifest_version(metadata, dataset, partition, manifest, *, required):
    """A shared file may satisfy several independently approved work scopes."""
    if "outputCompletion" not in metadata:
        return approved_input_version(metadata, dataset, partition, required=required)
    matches = [b for b in approved_input_bindings(metadata) if b["dataset"]==dataset and b["partition"]==partition]
    if not matches:
        raise ValueError("Missing approved work input")
    proofs = getattr(manifest, "workProofs", []) or ([{"workScope": manifest.workScope, "dataVersion": manifest.dataVersion}]
        if getattr(manifest, "workScope", None) else [])
    if {(p["workScope"]["atomicWorkId"], p["dataVersion"]) for p in proofs} != {(b["atomicWorkId"], b["dataVersion"]) for b in matches}:
        raise ValueError("Manifest does not contain the approved work scopes")
    return manifest.dataVersion
