FROM python:3.14-slim

WORKDIR /workspace

# Prevent Python from writing pyc files and enable unbuffered output
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_SYSTEM_PYTHON=1

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install uv
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.cargo/bin:$PATH"

# Copy workspace dependency files
COPY pyproject.toml uv.lock* ./
COPY libs/py-common/pyproject.toml libs/py-common/uv.lock* libs/py-common/
COPY apps/analyzer/pyproject.toml apps/analyzer/uv.lock* apps/analyzer/

# Install analyzer and workspace dependencies
RUN uv pip install --system --editable apps/analyzer

# Copy application and shared library code
COPY libs/py-common libs/py-common
COPY apps/analyzer apps/analyzer
COPY configs configs

WORKDIR /workspace/apps/analyzer

EXPOSE 8000

# Run with uvicorn
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
