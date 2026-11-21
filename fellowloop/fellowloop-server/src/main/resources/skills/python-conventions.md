---
name: python-conventions
description: Conventions for Python services and libraries
---
# Python

- Target the Python version the project declares; check `pyproject.toml` / `setup.cfg`.
- Type hints on public functions; dataclasses for plain data.
- Money: `decimal.Decimal` with explicit quantize and rounding; quantities as integers in base units.
- Datetimes: timezone-aware (`datetime.now(timezone.utc)`); never compare naive with aware values.
- No new third-party dependency unless the issue requires it.
- Run the project's test command (pytest or unittest) and keep it passing.
