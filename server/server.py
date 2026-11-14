#!/usr/bin/env python3
"""
RoadCatch development server.

For now it does one job: turn hazard-candidate coordinates into a short place name and a road type,
so the in-car app can show "신사동 도산대로" instead of raw coordinates. Later it is where candidates
from many vehicles get cross-checked.

Standard library only. Run:  python3 server.py   (port 8790, or GILZIKIM_PORT)
The Android emulator reaches this machine at http://10.0.2.2:8790.

Reverse geocoding uses OpenStreetMap Nominatim, which needs no key but allows at most one request
per second and asks for an identifying User-Agent. That is fine for development; production should
switch to a keyed provider (VWorld, Kakao) behind the same `reverse()` function.
"""

import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("GILZIKIM_PORT", "8790"))
USER_AGENT = "gilzikim-dev/0.1 (pleos contest prototype)"
NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"

# OSM highway types that are expressways. Urban expressways (e.g. 올림픽대로) are also tagged
# motorway but are run by city agencies, so this is only a hint for picking a reporting channel.
EXPRESSWAY_TYPES = {"motorway", "motorway_link"}

# Base map: OpenStreetMap standard tiles (Korean labels). The app turns them into a light gray
# "skeleton" look with a color filter. The OSM tile policy allows light development use with an
# identifying User-Agent, caching and attribution (© OpenStreetMap); production needs a licensed
# provider (VWorld, Kakao, Naver). CARTO now needs a key and Esri's gray canvas has no Korea data.
# The emulator has no network, so the app gets tiles through this server (adb reverse).
TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
TILE_PATH = re.compile(r"^/v1/tiles/(\d{1,2})/(\d+)/(\d+)\.png$")
TILE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tilecache")

_cache = {}
_lock = threading.Lock()
_last_request = [0.0]


def _cache_key(lat, lon):
    # ~11 m: candidates from different cars at the same spot share one lookup.
    return round(lat, 4), round(lon, 4)


def _nominatim(lat, lon):
    query = urllib.parse.urlencode({
        "format": "jsonv2",
        "lat": f"{lat:.6f}",
        "lon": f"{lon:.6f}",
        "zoom": 16,  # street level, so the result is the road itself
        "accept-language": "ko",
        "addressdetails": 1,
    })
    request = urllib.request.Request(f"{NOMINATIM_URL}?{query}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=8) as response:
        data = json.load(response)

    address = data.get("address") or {}
    area = address.get("quarter") or address.get("suburb") or address.get("neighbourhood") or address.get("city_district")
    road = address.get("road") or (data.get("name") if data.get("category") == "highway" else None)
    road_type = data.get("type") if data.get("category") == "highway" else None
    name = " ".join(dict.fromkeys(x for x in (area, road) if x)) or data.get("display_name")
    jurisdiction = "expressway" if road_type in EXPRESSWAY_TYPES else "local"
    district = address.get("borough") or address.get("city_district") or address.get("county")
    # Who to file a damage claim with. A hint, not a ruling: national roads and urban expressways
    # have their own managers.
    if jurisdiction == "expressway":
        agency = "한국도로공사"
    elif district:
        agency = f"{district}청"
    else:
        agency = address.get("city")
    return {
        "name": name,
        "area": area,
        "road": road,
        "roadType": road_type,
        "jurisdiction": jurisdiction,
        "agency": agency,
    }


def tile(z, x, y):
    """PNG bytes for one base map tile, from the disk cache or the provider. None on failure."""
    if z > 19 or x >= 2 ** z or y >= 2 ** z:
        return None
    path = os.path.join(TILE_DIR, str(z), str(x), f"{y}.png")
    if os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    request = urllib.request.Request(TILE_URL.format(z=z, x=x, y=y), headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            data = response.read()
    except Exception as error:
        print(f"tile failed {z}/{x}/{y}: {error}", flush=True)
        return None
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return data


def reverse(lat, lon):
    """Place info for a point, or None if the provider failed. Failures are not cached."""
    key = _cache_key(lat, lon)
    with _lock:
        if key in _cache:
            return _cache[key]
        wait = 1.0 - (time.monotonic() - _last_request[0])
        if wait > 0:
            time.sleep(wait)
        _last_request[0] = time.monotonic()
        try:
            result = _nominatim(lat, lon)
        except Exception as error:  # network, HTTP or JSON errors all mean "no name for now"
            print(f"reverse failed for {lat},{lon}: {error}", flush=True)
            return None
        _cache[key] = result
        return result


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
            return
        match = TILE_PATH.match(self.path)
        if not match:
            self._send(404, {"error": "not found"})
            return
        data = tile(*(int(g) for g in match.groups()))
        if data is None:
            self._send(502, {"error": "tile unavailable"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path != "/v1/places":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            points = json.loads(self.rfile.read(length) or b"{}").get("points", [])
            places = {}
            for p in points[:50]:
                info = reverse(float(p["lat"]), float(p["lon"]))
                if info:
                    places[str(p["id"])] = info
        except (ValueError, KeyError, TypeError) as error:
            self._send(400, {"error": f"bad request: {error}"})
            return
        self._send(200, {"places": places})

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)


if __name__ == "__main__":
    print(f"gilzikim dev server on :{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
