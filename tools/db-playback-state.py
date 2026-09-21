"""Inspects the app database: schema version, download columns and playback state."""
import os
import sqlite3
import sys

path = sys.argv[1] if len(sys.argv) > 1 else os.path.expandvars(r"%TEMP%\jf2.db")
connection = sqlite3.connect(path)

version = connection.execute("PRAGMA user_version").fetchone()[0]
print("schema version:", version)

has_download = connection.execute(
    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='download'"
).fetchone()[0]
if not has_download:
    print("no download table")
    sys.exit(0)

columns = [row[1] for row in connection.execute("PRAGMA table_info(download)")]
print("download columns:", ", ".join(columns))
print("playback columns present:", "position_ticks" in columns and "played" in columns)

rows = connection.execute(
    "SELECT item_id, status, position_ticks, played FROM download ORDER BY created_at DESC LIMIT 12"
).fetchall()
print(f"downloads: {len(rows)} shown")
for item_id, status, position_ticks, played in rows:
    print(f"  {item_id[:8]}  status={status:<11} position_ticks={position_ticks:<12} played={played}")
