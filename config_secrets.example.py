# Copy to config_secrets.py (gitignored). Prefer .env for keys; this exists
# only for compatibility with upstream config.py imports if needed.
import os

OPENWEATHER_API_KEY = os.environ.get("OPENWEATHER_API_KEY", "")
MBTA_API_KEY = os.environ.get("MBTA_API_KEY", "")
