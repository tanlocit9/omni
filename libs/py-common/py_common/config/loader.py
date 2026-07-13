"""YAML configuration file loader."""

from pathlib import Path
from typing import Any

import yaml


def load_yaml(path: Path) -> dict[str, Any]:
    """Load YAML configuration file.
    
    Args:
        path: Path to YAML file
        
    Returns:
        Dictionary containing parsed YAML content, or empty dict if file not found
        
    Examples:
        >>> from pathlib import Path
        >>> config = load_yaml(Path("config.yaml"))
        >>> config.get("kafka", {}).get("bootstrap-servers")
        'localhost:9092'
    """
    if not path.exists():
        return {}

    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f) or {}