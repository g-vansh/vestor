# BUILD LOG
Append a dated entry after each meaningful step: what you did, what worked, what changed.
Never record secret values — only that a secret was set.

---

## 2026-06-14 — Dry session plan (no hardware)

**Goal of this session:** every software/repo/config/script task achievable with NO LED
panels attached. STOP before any live panel hardware test (hard stop #5).

**Environment discovered (Mac, this session):**
- git 2.50.1, gh 2.83.2 **authenticated as `g-vansh`** (scopes: repo, workflow) → can create + push the repo autonomously.
- brew 5.1.11, python3 3.14.5, openssl 3.6.2.
- Installed this session: shellcheck 0.11.0, Raspberry Pi Imager (cask).
- **No SD card present** (only internal disk0/disk9 + disk images) → flashing is PENDING (hard stop #1, no card to risk).
- **Pi not reachable** (`flightwall.local` / `flightpi.local` both silent) → all Pi-side steps PENDING.
- Network to github.com OK.

**Plan (mirrors AGENTS.md §7 / master prompt execution plan):**
1. [done] Read AGENTS.md + scaffold + setup guide. Write this plan.
2. [done] Mac tooling: shellcheck + rpi-imager installed & verified.
3. Repo: cloned upstream `ColinWaddell/its-a-plane-python` → `flightwall`, renamed origin→`upstream`. Overlay scaffold. Create `g-vansh/flightwall`, push main.
4. Overlay scaffold files/scripts; verify tree.
5. GPL: keep upstream `LICENSE`; finalize `NOTICE.md` attribution.
6. Edit `display/__init__.py` → Triple Bonnet options (regular / FM6126A / parallel=1 test, 3 wall / HW pulsing on).
7. Write `config.py` (540 Memorial Drive geometry + Pi4 tuning, no secrets).
8. Secrets: gitignored `.env` + `config_secrets.py` placeholders; commit only `.example` templates; verify git status clean.
9. Flicker/quality tweaks in scripts; validate `bash -n` + shellcheck.
10. systemd service: venv python, not root, correct entry point.
11. SD flashing prep: `flash_sd.sh` + `custom.toml` (mark flashing PENDING — no card).
12. Pi-side steps → PENDING in OPEN_QUESTIONS.md with exact commands.
13. Final verification + report.

**Key decisions made this session (recorded, not stalled):**
- **Repo strategy:** clone-and-re-point (not fork). Keeps full upstream history + lets us `git fetch upstream` later. `origin` → `g-vansh/flightwall`, `upstream` → ColinWaddell.
- **Entry point:** upstream's real entry is `flight-tracker.py` (NOT `main.py`). The scaffold `services/flightwall.service` shipped `main.py` — **corrected to `flight-tracker.py`**. (Discrepancy with scaffold; the actual file on disk wins.)
- **Hostname:** master prompt says `flightwall` (`flightwall.local`); AGENTS.md + setup guide say `flightpi`. **Chose `flightwall`** (matches repo/project name, master prompt is the immediate instruction). Standardized scripts/docs/.env to `flightwall.local`. Owner can override — see OPEN_QUESTIONS.md.
- **Secrets in config.py:** `config.py` is committed and must stay secret-free, but `scenes/weather.py` does `from config import OPENWEATHER_API_KEY`. Resolved by reading keys from `os.environ` in `config.py` (`os.environ.get(...)`), populated by `.env` via the systemd `EnvironmentFile`. No gitignored-file import at module top (would crash the whole app if missing).
- **gpio_slowdown / brightness in display:** kept upstream's config-driven `options.gpio_slowdown = GPIO_SLOWDOWN` / `options.brightness = BRIGHTNESS` (config.py sets GPIO_SLOWDOWN=4, BRIGHTNESS=50) rather than hardcoding — resolves to the prompt's values while staying DRY.

**config.py symbol contract (from grepping the codebase):** required `WEATHER_LOCATION`,
`ZONE_HOME`, `LOCATION_HOME`, `BRIGHTNESS`, `GPIO_SLOWDOWN`, `HAT_PWM_ENABLED`; used-optional
`TEMPERATURE_UNITS`, `MIN_ALTITUDE`, `JOURNEY_CODE_SELECTED`, `JOURNEY_BLANK_FILLER`,
`OPENWEATHER_API_KEY`. (`RAINFALL_ENABLED`, `LOADING_LED_ENABLED/_GPIO_PIN` are guarded by
try/except in the code and intentionally left unset → disabled.)

---

## Log entries

### 2026-06-14 — Repo established
- Did: cloned upstream → `flightwall`; `git remote rename origin upstream`; installed shellcheck + rpi-imager; overlaid scaffold (AGENTS, README, NOTICE, .gitignore, .env.example, config_secrets.example, docs/, scripts/, services/).
- Verified: `git remote -v` shows `upstream` → ColinWaddell; `LICENSE` present (GPL-3.0, 35 KB); tree matches intended layout.
- Changed from brief: none structural. Scaffold `.gitignore` replaces upstream's (which ignored `config.py` — we commit `config.py`).
- Next: display edit, config.py, secrets, scripts, service, push.

### 2026-06-14 — Config, code edits, scripts, service (all dry)
- Did:
  - `display/__init__.py`: replaced single-bonnet options with Triple Bonnet active3
    (`hardware_mapping="regular"`, `panel_type="FM6126A"` added, `chain_length=1`/`parallel=1`
    for Phase 0 with wall values commented, `disable_hardware_pulsing=False`, `drop_privileges=True`).
  - `config.py`: 540 Memorial Drive geometry (ZONE_HOME/LOCATION_HOME), MIN_ALTITUDE=100,
    BRIGHTNESS=50, GPIO_SLOWDOWN=4, JOURNEY_CODE_SELECTED="BOS", weather Cambridge,MA,US;
    keys read from os.environ (no secrets committed).
  - Secrets: created gitignored `.env` + `config_secrets.py` (placeholders, chmod 600);
    committed only `.env.example` + `config_secrets.example.py`.
  - Scripts: rewrote `flash_sd.sh` (canonical Bookworm custom.toml, refuses internal disk,
    requires ERASE + filled toml); added `scripts/custom.toml.example`; `.gitignore` now
    blocks the filled `custom.toml`. `setup_pi.sh` already covers blacklist snd_bcm2835 +
    audio=off + isolcpus=3 (with .bak backups, single-line append).
  - `services/flightwall.service`: corrected entry point `main.py`→`flight-tracker.py`,
    added PYTHONUNBUFFERED; runs as `pi` (not root).
- Verified:
  - `python3 -m py_compile` clean on display/__init__.py + config.py; config symbol contract PASS.
  - display grep shows regular / FM6126A / parallel / drop_privileges.
  - `bash -n` + `shellcheck` clean on all 4 scripts.
  - `git check-ignore` confirms `.env` + `config_secrets.py` ignored; `git status` shows no secrets.
  - systemd unit structural check PASS (not root, flight-tracker.py present).
- Changed from brief: entry point `main.py`→`flight-tracker.py`; hostname `flightpi`→`flightwall` (repo-wide).
- Next: commit in logical groups, create `g-vansh/flightwall`, push main. Flashing + all Pi steps PENDING (no card, Pi offline).

## (template)
### YYYY-MM-DD — <step>
- Did:
- Verified:
- Changed from brief:
- Next:
