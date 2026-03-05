#!/usr/bin/env python3
"""
TripPlanner Full-Stack E2E Testing Agent
=========================================
Powered by Claude claude-opus-4-6 with adaptive thinking.

Usage:
  python agent.py
  python agent.py --prompt "Test only the search and booking flows"
  python agent.py --backend-url http://localhost:9090 --frontend-url http://localhost:3001

Environment:
  ANTHROPIC_API_KEY  — required
  BACKEND_URL        — optional, default http://localhost:8080
  FRONTEND_URL       — optional, default http://localhost:3000
"""

import anthropic
import subprocess
import time
import json
import os
import signal
import sys
import argparse
import requests
from pathlib import Path
from datetime import datetime
from typing import Optional, Any

# ==================== Configuration ====================

BASE_DIR    = Path(__file__).parent.parent
BACKEND_DIR = BASE_DIR / "backend"
FRONTEND_DIR = BASE_DIR / "frontend"
SCREENSHOTS_DIR = BASE_DIR / "screenshots"
REPORTS_DIR = BASE_DIR / "reports"

# Override via env or --flags
BACKEND_URL  = os.environ.get("BACKEND_URL",  "http://localhost:8080")
FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")

# ==================== Global State ====================

_processes: dict[str, subprocess.Popen] = {}

_browser: dict[str, Any] = {
    "playwright": None,
    "browser":    None,
    "page":       None,
}

# ==================== Helpers ====================

def _j(data: dict) -> str:
    return json.dumps(data)

def _ok(message: str = "ok", **kw) -> str:
    return _j({"status": "ok", "message": message, **kw})

def _err(message: str) -> str:
    return _j({"status": "error", "message": message})

def _is_unix() -> bool:
    return sys.platform != "win32"

# ==================== Service Management ====================

def start_backend() -> str:
    if "backend" in _processes and _processes["backend"].poll() is None:
        return _ok("Backend already running", pid=_processes["backend"].pid)

    if not BACKEND_DIR.exists():
        return _err(f"backend/ directory not found at {BACKEND_DIR}")

    if not (BACKEND_DIR / "pom.xml").exists():
        return _err("No pom.xml in backend/. Add your Spring Boot project first.")

    try:
        proc = subprocess.Popen(
            ["mvn", "spring-boot:run", "-q"],
            cwd=str(BACKEND_DIR),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            preexec_fn=os.setsid if _is_unix() else None,
        )
        _processes["backend"] = proc
        return _ok("Spring Boot backend starting", pid=proc.pid, url=BACKEND_URL)
    except FileNotFoundError:
        return _err("'mvn' not found. Install Maven: https://maven.apache.org/install.html")
    except Exception as e:
        return _err(str(e))


def start_frontend() -> str:
    if "frontend" in _processes and _processes["frontend"].poll() is None:
        return _ok("Frontend already running", pid=_processes["frontend"].pid)

    if not FRONTEND_DIR.exists():
        return _err(f"frontend/ directory not found at {FRONTEND_DIR}")

    if not (FRONTEND_DIR / "package.json").exists():
        return _err("No package.json in frontend/. Add your Next.js project first.")

    try:
        if not (FRONTEND_DIR / "node_modules").exists():
            print("  📦 Installing frontend deps (first run)…")
            result = subprocess.run(
                ["npm", "install"],
                cwd=str(FRONTEND_DIR),
                capture_output=True,
                text=True,
                timeout=180,
            )
            if result.returncode != 0:
                return _err(f"npm install failed: {result.stderr[:400]}")

        proc = subprocess.Popen(
            ["npm", "run", "dev"],
            cwd=str(FRONTEND_DIR),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env={**os.environ, "PORT": "3000"},
            preexec_fn=os.setsid if _is_unix() else None,
        )
        _processes["frontend"] = proc
        return _ok("Next.js frontend starting", pid=proc.pid, url=FRONTEND_URL)
    except FileNotFoundError:
        return _err("'npm' not found. Install Node.js: https://nodejs.org")
    except Exception as e:
        return _err(str(e))


def wait_for_service(url: str, timeout: int = 120, interval: int = 3) -> str:
    start = time.time()
    while time.time() - start < timeout:
        try:
            r = requests.get(url, timeout=3, allow_redirects=True)
            if r.status_code < 500:
                elapsed = round(time.time() - start, 1)
                return _ok(f"Service ready after {elapsed}s", url=url, elapsed=elapsed)
        except (requests.ConnectionError, requests.Timeout):
            pass
        time.sleep(interval)
    return _err(f"Service at {url} not ready after {timeout}s")


def check_service_health(url: str) -> str:
    try:
        r = requests.get(url, timeout=5)
        return _j({
            "status": "healthy" if r.status_code < 500 else "unhealthy",
            "url": url,
            "http_status": r.status_code,
        })
    except requests.ConnectionError:
        return _j({"status": "down", "url": url, "message": "Connection refused"})
    except Exception as e:
        return _err(str(e))


def stop_all_services() -> str:
    stopped, failed = [], []
    for name, proc in list(_processes.items()):
        try:
            if _is_unix():
                os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            else:
                proc.terminate()
            proc.wait(timeout=10)
            stopped.append(name)
        except Exception as e:
            failed.append(f"{name}: {e}")
        _processes.pop(name, None)

    _close_browser()
    return _j({"status": "ok", "stopped": stopped, "failed": failed})


# ==================== Browser Automation ====================

def _init_browser() -> tuple[bool, str]:
    if _browser["browser"] is not None:
        return True, ""
    try:
        from playwright.sync_api import sync_playwright
        pw = sync_playwright().start()
        b  = pw.chromium.launch(headless=True, args=["--no-sandbox"])
        p  = b.new_page(viewport={"width": 1280, "height": 800})
        p.set_default_timeout(15_000)
        _browser.update(playwright=pw, browser=b, page=p)
        SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)
        return True, ""
    except ImportError:
        return False, (
            "Playwright not installed. Run:\n"
            "  pip install playwright && playwright install chromium"
        )
    except Exception as e:
        return False, str(e)


def _close_browser():
    if _browser["browser"]:
        try:
            _browser["browser"].close()
            if _browser["playwright"]:
                _browser["playwright"].stop()
        except Exception:
            pass
    _browser.update(playwright=None, browser=None, page=None)


def _page():
    return _browser["page"]


def navigate_to_url(url: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        resp = _page().goto(url, wait_until="domcontentloaded", timeout=30_000)
        return _j({
            "status": "ok",
            "url": _page().url,
            "title": _page().title(),
            "http_status": resp.status if resp else None,
        })
    except Exception as e:
        return _err(str(e))


def take_screenshot(filename: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        safe = "".join(c for c in filename if c.isalnum() or c in "-_")
        path = SCREENSHOTS_DIR / f"{safe}.png"
        _page().screenshot(path=str(path), full_page=True)
        return _j({"status": "ok", "path": str(path), "filename": f"{safe}.png"})
    except Exception as e:
        return _err(str(e))


def click_element(selector: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        _page().click(selector, timeout=10_000)
        return _ok(f"Clicked: {selector}")
    except Exception as e:
        return _err(f"Click failed '{selector}': {e}")


def fill_input(selector: str, value: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        _page().fill(selector, value, timeout=10_000)
        return _ok(f"Filled '{selector}'")
    except Exception as e:
        return _err(f"Fill failed '{selector}': {e}")


def get_page_text() -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        text = _page().inner_text("body")
        return _j({"status": "ok", "url": _page().url, "text": text[:5000]})
    except Exception as e:
        return _err(str(e))


def verify_element_present(selector: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        count = _page().locator(selector).count()
        return _j({"status": "ok", "selector": selector, "present": count > 0, "count": count})
    except Exception as e:
        return _err(str(e))


def verify_text_on_page(text: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        found = text.lower() in _page().content().lower()
        return _j({"status": "ok", "text": text, "found": found})
    except Exception as e:
        return _err(str(e))


def wait_for_element(selector: str, timeout: int = 10_000) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        _page().wait_for_selector(selector, timeout=timeout)
        return _ok(f"Element visible: {selector}")
    except Exception as e:
        return _err(f"Timeout for '{selector}': {e}")


def select_option(selector: str, value: str) -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        _page().select_option(selector, value, timeout=10_000)
        return _ok(f"Selected '{value}' in '{selector}'")
    except Exception as e:
        return _err(str(e))


def get_page_url() -> str:
    ok, err = _init_browser()
    if not ok:
        return _err(err)
    try:
        return _j({"status": "ok", "url": _page().url, "title": _page().title()})
    except Exception as e:
        return _err(str(e))


# ==================== API Testing ====================

def make_api_request(
    method: str,
    path: str,
    body: Optional[dict] = None,
    headers: Optional[dict] = None,
) -> str:
    url = f"{BACKEND_URL}{path}" if not path.startswith("http") else path
    h = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        h.update(headers)
    try:
        r = requests.request(method.upper(), url, json=body, headers=h, timeout=15)
        try:
            resp_body = r.json()
        except Exception:
            resp_body = r.text[:1000]
        return _j({
            "status": "ok",
            "http_status": r.status_code,
            "url": url,
            "method": method.upper(),
            "response": resp_body,
        })
    except requests.ConnectionError:
        return _err(f"Cannot connect to {url}")
    except Exception as e:
        return _err(str(e))


# ==================== Project Exploration ====================

_IGNORE_DIRS = {
    ".git", "node_modules", "target", ".next", "__pycache__",
    ".mvn", ".idea", "dist", "build", ".gradle", ".cache",
}

def read_project_structure() -> str:
    def scan(path: Path, depth: int = 0, max_depth: int = 4) -> list:
        if depth >= max_depth or not path.exists():
            return []
        items = []
        try:
            for entry in sorted(path.iterdir(), key=lambda x: (x.is_file(), x.name)):
                if entry.name in _IGNORE_DIRS:
                    continue
                if entry.name.startswith(".") and entry.name not in (
                    ".env.example", ".env.local.example", ".gitignore"
                ):
                    continue
                if entry.is_dir():
                    items.append({
                        "type": "dir",
                        "name": entry.name,
                        "children": scan(entry, depth + 1, max_depth),
                    })
                else:
                    items.append({
                        "type": "file",
                        "name": entry.name,
                        "size_bytes": entry.stat().st_size,
                    })
        except PermissionError:
            pass
        return items

    return _j({
        "backend":  {"path": str(BACKEND_DIR),  "files": scan(BACKEND_DIR)},
        "frontend": {"path": str(FRONTEND_DIR), "files": scan(FRONTEND_DIR)},
    })


def read_file_content(filepath: str) -> str:
    try:
        path = (BASE_DIR / filepath).resolve()
        if not str(path).startswith(str(BASE_DIR.resolve())):
            return _err("Access denied: path outside project root")
        if not path.exists():
            return _err(f"File not found: {filepath}")
        size = path.stat().st_size
        if size > 100_000:
            return _err(f"File too large ({size} bytes, max 100 KB)")
        content = path.read_text(encoding="utf-8", errors="replace")
        return _j({"status": "ok", "filepath": filepath, "size": size, "content": content})
    except Exception as e:
        return _err(str(e))


# ==================== Reporting ====================

def save_test_report(title: str, results: list, summary: str) -> str:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")

    passed   = sum(1 for r in results if r.get("passed", False))
    failed   = len(results) - passed
    total    = len(results)
    pct      = round(passed / total * 100, 1) if total else 0
    color    = "#4caf50" if pct >= 80 else "#ff9800" if pct >= 50 else "#f44336"

    # ---- JSON ----
    data = {
        "title": title,
        "timestamp": datetime.now().isoformat(),
        "summary": summary,
        "stats": {"total": total, "passed": passed, "failed": failed, "pass_rate": pct},
        "results": results,
    }
    json_path = REPORTS_DIR / f"report_{ts}.json"
    json_path.write_text(json.dumps(data, indent=2))

    # ---- HTML ----
    rows = ""
    for r in results:
        badge  = "✅ PASS" if r.get("passed") else "❌ FAIL"
        cls    = "pass" if r.get("passed") else "fail"
        sc_raw = r.get("screenshot", "")
        sc_safe = "".join(c for c in sc_raw if c.isalnum() or c in "-_") if sc_raw else ""
        sc_link = (
            f'<a href="../screenshots/{sc_safe}.png" target="_blank">📸 View</a>'
            if sc_safe else ""
        )
        rows += (
            f'<tr class="{cls}">'
            f"<td>{r.get('scenario','')}</td>"
            f"<td>{badge}</td>"
            f"<td>{r.get('details','')}</td>"
            f"<td>{sc_link}</td>"
            f"</tr>\n"
        )

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>{title}</title>
  <style>
    *{{box-sizing:border-box;margin:0;padding:0}}
    body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f5f7fa}}
    .hdr{{background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;padding:30px 40px}}
    .hdr h1{{font-size:26px;margin-bottom:6px}}
    .hdr p{{opacity:.8;font-size:14px}}
    .wrap{{max-width:1100px;margin:24px auto;padding:0 24px}}
    .cards{{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:24px}}
    .card{{background:#fff;border-radius:12px;padding:20px;flex:1;min-width:140px;
           box-shadow:0 2px 8px rgba(0,0,0,.07);text-align:center}}
    .num{{font-size:34px;font-weight:700}}
    .lbl{{color:#666;font-size:13px;margin-top:4px}}
    .summary{{background:#fff;border-radius:12px;padding:20px;margin-bottom:24px;
              box-shadow:0 2px 8px rgba(0,0,0,.07)}}
    .summary h3{{margin-bottom:10px;color:#333}}
    .summary p{{color:#555;line-height:1.6}}
    table{{width:100%;border-collapse:collapse;background:#fff;border-radius:12px;
           overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.07)}}
    th{{background:#667eea;color:#fff;padding:14px 16px;text-align:left;font-weight:600}}
    td{{padding:12px 16px;border-bottom:1px solid #f0f0f0;font-size:14px}}
    tr:last-child td{{border-bottom:none}}
    tr.pass{{background:#f0fff4}} tr.fail{{background:#fff5f5}}
    a{{color:#667eea;text-decoration:none}} a:hover{{text-decoration:underline}}
  </style>
</head>
<body>
<div class="hdr">
  <h1>🧪 {title}</h1>
  <p>Generated {datetime.now().strftime("%B %d, %Y at %H:%M:%S")}</p>
</div>
<div class="wrap">
  <div class="cards">
    <div class="card"><div class="num" style="color:{color}">{pct}%</div><div class="lbl">Pass Rate</div></div>
    <div class="card"><div class="num" style="color:#4caf50">{passed}</div><div class="lbl">Passed</div></div>
    <div class="card"><div class="num" style="color:#f44336">{failed}</div><div class="lbl">Failed</div></div>
    <div class="card"><div class="num" style="color:#667eea">{total}</div><div class="lbl">Total</div></div>
  </div>
  <div class="summary"><h3>Summary</h3><p>{summary}</p></div>
  <table>
    <thead><tr><th>Test Scenario</th><th>Result</th><th>Details</th><th>Screenshot</th></tr></thead>
    <tbody>{rows}</tbody>
  </table>
</div>
</body></html>
"""
    html_path = REPORTS_DIR / f"report_{ts}.html"
    html_path.write_text(html)

    return _j({
        "status": "ok",
        "html_report": str(html_path),
        "json_report": str(json_path),
        "stats": {"total": total, "passed": passed, "failed": failed, "pass_rate": pct},
    })


# ==================== Tool Definitions ====================

TOOLS = [
    {
        "name": "start_backend",
        "description": (
            "Start the Spring Boot backend service using 'mvn spring-boot:run'. "
            "Requires pom.xml in backend/. Returns the PID and startup URL."
        ),
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "start_frontend",
        "description": (
            "Start the Next.js frontend with 'npm run dev'. "
            "Installs node_modules first if missing. Returns PID and URL."
        ),
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "wait_for_service",
        "description": (
            "Poll a URL until the service responds (HTTP < 500). "
            "Use immediately after start_backend / start_frontend."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "URL to poll, e.g. http://localhost:8080/actuator/health",
                },
                "timeout": {
                    "type": "integer",
                    "description": "Max seconds to wait (default 120)",
                },
            },
            "required": ["url"],
        },
    },
    {
        "name": "check_service_health",
        "description": "Quick one-shot health check: returns healthy/unhealthy/down.",
        "input_schema": {
            "type": "object",
            "properties": {
                "url": {"type": "string"},
            },
            "required": ["url"],
        },
    },
    {
        "name": "stop_all_services",
        "description": (
            "Terminate backend + frontend processes and close the browser. "
            "Always call this as the very last step."
        ),
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "navigate_to_url",
        "description": "Open a URL in the headless Chromium browser.",
        "input_schema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "Full URL including protocol"},
            },
            "required": ["url"],
        },
    },
    {
        "name": "take_screenshot",
        "description": (
            "Capture a full-page screenshot of the current browser page. "
            "Use descriptive names like '01_homepage', '02_search_results'."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "filename": {
                    "type": "string",
                    "description": "File name without extension, e.g. '03_booking_form'",
                },
            },
            "required": ["filename"],
        },
    },
    {
        "name": "click_element",
        "description": "Click a page element by CSS selector or Playwright text/role locator.",
        "input_schema": {
            "type": "object",
            "properties": {
                "selector": {
                    "type": "string",
                    "description": "e.g. 'button[type=submit]', 'text=Sign In', '[data-testid=login]'",
                },
            },
            "required": ["selector"],
        },
    },
    {
        "name": "fill_input",
        "description": "Clear and type a value into an input or textarea.",
        "input_schema": {
            "type": "object",
            "properties": {
                "selector": {"type": "string", "description": "CSS selector for the input"},
                "value":    {"type": "string", "description": "Text to type"},
            },
            "required": ["selector", "value"],
        },
    },
    {
        "name": "get_page_text",
        "description": "Return visible text content of the current page (up to 5 000 chars).",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "get_page_url",
        "description": "Return the current browser URL and page title.",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "verify_element_present",
        "description": "Check whether a CSS selector matches any element on the page.",
        "input_schema": {
            "type": "object",
            "properties": {"selector": {"type": "string"}},
            "required": ["selector"],
        },
    },
    {
        "name": "verify_text_on_page",
        "description": "Check whether a string appears anywhere in the page HTML.",
        "input_schema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "wait_for_element",
        "description": "Wait until a CSS selector appears (useful after navigation or async renders).",
        "input_schema": {
            "type": "object",
            "properties": {
                "selector": {"type": "string"},
                "timeout":  {"type": "integer", "description": "Milliseconds (default 10 000)"},
            },
            "required": ["selector"],
        },
    },
    {
        "name": "select_option",
        "description": "Select a value from a <select> dropdown.",
        "input_schema": {
            "type": "object",
            "properties": {
                "selector": {"type": "string"},
                "value":    {"type": "string", "description": "Option value or label"},
            },
            "required": ["selector", "value"],
        },
    },
    {
        "name": "make_api_request",
        "description": (
            "Send an HTTP request directly to the backend API. "
            "Use this for REST endpoint testing independent of the UI."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "method": {
                    "type": "string",
                    "enum": ["GET", "POST", "PUT", "DELETE", "PATCH"],
                },
                "path": {
                    "type": "string",
                    "description": "Path like /api/trips or full URL",
                },
                "body":    {"type": "object", "description": "JSON request body"},
                "headers": {"type": "object", "description": "Extra HTTP headers"},
            },
            "required": ["method", "path"],
        },
    },
    {
        "name": "read_project_structure",
        "description": (
            "Scan backend/ and frontend/ directories to understand the codebase. "
            "Always call this first so you know which routes, controllers, and pages exist."
        ),
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "read_file_content",
        "description": "Read a file to inspect source code, routes, or config (max 100 KB).",
        "input_schema": {
            "type": "object",
            "properties": {
                "filepath": {
                    "type": "string",
                    "description": "Path relative to project root, e.g. 'frontend/src/app/page.tsx'",
                },
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "save_test_report",
        "description": (
            "Generate an HTML + JSON test report and save it to reports/. "
            "Call this once you have finished running all test scenarios."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "title":   {"type": "string", "description": "Report heading"},
                "summary": {"type": "string", "description": "Plain-text overall summary"},
                "results": {
                    "type": "array",
                    "description": "One object per test scenario",
                    "items": {
                        "type": "object",
                        "properties": {
                            "scenario":   {"type": "string"},
                            "passed":     {"type": "boolean"},
                            "details":    {"type": "string"},
                            "screenshot": {
                                "type": "string",
                                "description": "Screenshot filename WITHOUT .png",
                            },
                        },
                        "required": ["scenario", "passed"],
                    },
                },
            },
            "required": ["title", "results", "summary"],
        },
    },
]


# ==================== Tool Router ====================

def _exec(name: str, inp: dict) -> str:
    try:
        match name:
            case "start_backend":          return start_backend()
            case "start_frontend":         return start_frontend()
            case "wait_for_service":       return wait_for_service(inp["url"], inp.get("timeout", 120))
            case "check_service_health":   return check_service_health(inp["url"])
            case "stop_all_services":      return stop_all_services()
            case "navigate_to_url":        return navigate_to_url(inp["url"])
            case "take_screenshot":        return take_screenshot(inp["filename"])
            case "click_element":          return click_element(inp["selector"])
            case "fill_input":             return fill_input(inp["selector"], inp["value"])
            case "get_page_text":          return get_page_text()
            case "get_page_url":           return get_page_url()
            case "verify_element_present": return verify_element_present(inp["selector"])
            case "verify_text_on_page":    return verify_text_on_page(inp["text"])
            case "wait_for_element":       return wait_for_element(inp["selector"], inp.get("timeout", 10_000))
            case "select_option":          return select_option(inp["selector"], inp["value"])
            case "make_api_request":       return make_api_request(inp["method"], inp["path"], inp.get("body"), inp.get("headers"))
            case "read_project_structure": return read_project_structure()
            case "read_file_content":      return read_file_content(inp["filepath"])
            case "save_test_report":       return save_test_report(inp["title"], inp["results"], inp["summary"])
            case _:                        return _err(f"Unknown tool: {name}")
    except Exception as e:
        return _err(f"Tool '{name}' raised: {e}")


# ==================== System Prompt ====================

SYSTEM_PROMPT = """You are an expert full-stack QA automation engineer embedded in the TripPlanner project.

## Your objective
Run a thorough end-to-end test suite for a Next.js + Spring Boot web application and produce a
professional HTML test report with screenshots.

## Step-by-step process

### Phase 1 – Discover
1. `read_project_structure` — map out every file and directory.
2. `read_file_content` on key files:
   - Spring Boot: controllers (`@RestController`), entity classes, `application.properties`
   - Next.js: `package.json`, route files under `src/app/` or `pages/`, API routes
3. Build a mental model of: API endpoints, UI pages, user flows.

### Phase 2 – Launch services
4. `start_backend` → `wait_for_service` (backend URL)
5. `start_frontend` → `wait_for_service` (frontend URL)

### Phase 3 – API tests (no browser needed)
For each discovered REST endpoint:
- `make_api_request` GET/POST/PUT/DELETE
- Cover happy path, validation errors (400), not-found (404)

### Phase 4 – UI / E2E tests
For each page / user flow:
- `navigate_to_url` → `take_screenshot` (numbered, descriptive)
- Fill forms, click buttons, verify results
- `verify_text_on_page` / `verify_element_present` for assertions
- Common flows to test (adapt to what you find):
  * Homepage renders correctly
  * Search / filter (empty query, valid query, no results)
  * Create / view / edit / delete a resource
  * Form validation (missing fields, invalid formats)
  * Navigation links and 404 handling
  * Authentication (login, logout, protected routes) — if present

### Phase 5 – Report
- `save_test_report` with ALL results (one entry per scenario).
- `stop_all_services` as the very last action.

## Screenshot naming
Use zero-padded sequential prefix + description:
  01_homepage, 02_search_empty, 03_search_results, 04_trip_detail, …

## Result recording
For every scenario record:
  - scenario: short description ("Search returns no results for unknown city")
  - passed: true/false
  - details: what was checked / what went wrong
  - screenshot: filename used with take_screenshot (omit .png)

## Constraints
- Always stop services at the end — even if tests fail.
- If a service cannot start (empty directory, missing pom.xml, etc.) document it in the
  report and skip the dependent tests; do NOT crash the agent.
- Max 80 tool calls to stay within budget.
"""

# ==================== Main Agent Loop ====================

def run_agent(prompt: Optional[str] = None) -> None:
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        print("❌  ANTHROPIC_API_KEY is not set.")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)

    user_msg = prompt or (
        "Run a full E2E test suite for the TripPlanner application.\n"
        "Start by reading the project structure to understand what exists,\n"
        "then start both services, test all features with screenshots,\n"
        "and save a detailed HTML report."
    )

    messages: list[dict] = [{"role": "user", "content": user_msg}]

    print("\n" + "━" * 62)
    print("  🚀  TripPlanner E2E Testing Agent  (Claude claude-opus-4-6)")
    print("━" * 62 + "\n")

    MAX_ITERS = 120
    for iteration in range(1, MAX_ITERS + 1):
        try:
            response = client.messages.create(
                model="claude-opus-4-6",
                max_tokens=8192,
                thinking={"type": "adaptive"},
                system=SYSTEM_PROMPT,
                tools=TOOLS,
                messages=messages,
            )
        except anthropic.APIError as e:
            print(f"\n❌  API error: {e}")
            break

        # ---- Display agent output ----
        for block in response.content:
            btype = getattr(block, "type", "")
            if btype == "thinking":
                preview = block.thinking.replace("\n", " ")[:180]
                print(f"💭  {preview}…")
            elif btype == "text" and block.text.strip():
                print(f"\n🤖  {block.text}\n")

        # ---- Done? ----
        if response.stop_reason == "end_turn":
            print("\n" + "━" * 62)
            print("  ✅  Agent finished.")
            print("━" * 62)
            break

        # ---- Handle tool calls ----
        if response.stop_reason == "tool_use":
            tool_results = []
            for block in response.content:
                if getattr(block, "type", "") != "tool_use":
                    continue

                args_str = json.dumps(block.input)
                preview  = args_str[:100] + ("…" if len(args_str) > 100 else "")
                print(f"🔧  {block.name}({preview})")

                result_str = _exec(block.name, block.input)

                # Print a compact result line
                try:
                    rd = json.loads(result_str)
                    st = rd.get("status", "")
                    if st in ("ok", "healthy", "ready", "started", "already_running"):
                        print(f"   ✓  {rd.get('message', st)}")
                    elif st == "error":
                        print(f"   ✗  {rd.get('message', 'error')}")
                    elif st == "down":
                        print(f"   ↓  {rd.get('message', 'service down')}")
                    else:
                        print(f"   →  {result_str[:160]}")
                except Exception:
                    print(f"   →  {result_str[:160]}")

                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": result_str,
                })

            messages.append({"role": "assistant", "content": response.content})
            messages.append({"role": "user",      "content": tool_results})

        else:
            print(f"⚠️   Unexpected stop_reason: {response.stop_reason}")
            break

    else:
        print(f"\n⚠️   Reached {MAX_ITERS}-iteration safety limit.")

    # Ensure cleanup regardless of how the loop ended
    if _processes or _browser["browser"]:
        print("\n🧹  Cleaning up lingering processes…")
        stop_all_services()


# ==================== Entry Point ====================

def main() -> None:
    global BACKEND_URL, FRONTEND_URL

    parser = argparse.ArgumentParser(
        description="TripPlanner Full-Stack E2E Testing Agent",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("-p", "--prompt",       help="Custom test instruction")
    parser.add_argument("--backend-url",        help="Override backend base URL")
    parser.add_argument("--frontend-url",       help="Override frontend base URL")
    args = parser.parse_args()

    if args.backend_url:
        BACKEND_URL = args.backend_url
    if args.frontend_url:
        FRONTEND_URL = args.frontend_url

    try:
        run_agent(args.prompt)
    except KeyboardInterrupt:
        print("\n\n⚠️   Interrupted by user. Cleaning up…")
        stop_all_services()
        sys.exit(0)


if __name__ == "__main__":
    main()
