import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_LOG = Path(os.environ.get("APPDATA", str(Path.home()))) / "PrismLauncher" / "instances" / "dev test mod" / "minecraft" / "config" / "bazaar-advisor" / "flip-feedback.jsonl"
LOG_PATH = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else DEFAULT_LOG
LOCK = threading.Lock()

PAGE = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bazaar Advisor | Flip feedback</title>
<style>
:root{color-scheme:dark;--bg:#101719;--surface:#192326;--line:#304044;--text:#e7efec;--muted:#a6b4b0;--mint:#a8e6c1;--amber:#f4c969;--red:#ff9185;font:15px/1.45 "Segoe UI",sans-serif}*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 15% 0%,#26403c 0,transparent 38%),var(--bg);color:var(--text)}main{max-width:1120px;margin:auto;padding:28px 20px}header{display:flex;justify-content:space-between;align-items:center;gap:18px;border-bottom:1px solid var(--line);padding-bottom:18px}h1{font-size:24px;margin:0;color:var(--mint)}.sub,.muted{color:var(--muted)}.tools{display:flex;gap:8px;align-items:center}.tools button,select,textarea,.save{background:#202d30;color:var(--text);border:1px solid #43575a;border-radius:4px;padding:8px 10px}button,.save{cursor:pointer}.tools button:hover,.save:hover{border-color:var(--mint)}.summary{display:flex;gap:20px;color:var(--muted);padding:14px 0}.summary strong{color:var(--text)}.list{display:grid;gap:10px}.flip{display:grid;grid-template-columns:minmax(240px,1fr) minmax(280px,.85fr);gap:16px;background:var(--surface);border:1px solid var(--line);border-left:3px solid #567d6a;padding:15px}.flip.rated{border-left-color:var(--amber)}h2{font-size:17px;margin:0 0 8px}.facts{display:flex;flex-wrap:wrap;gap:7px 16px;color:var(--muted);font-size:13px}.profit{font-weight:700;color:var(--mint)}.rating{display:flex;gap:8px;align-items:flex-start}.rating select{min-width:92px}.rating textarea{flex:1;min-height:60px;resize:vertical}.save{background:#254439;border-color:#4e8c70;color:#eafff3;white-space:nowrap}.saved{color:var(--mint);font-size:12px;min-height:18px}.empty{padding:28px;color:var(--muted);text-align:center;border:1px dashed var(--line)}@media(max-width:720px){header{align-items:flex-start;flex-direction:column}.flip{grid-template-columns:1fr}.summary{flex-wrap:wrap}}
</style></head><body><main>
<header><div><h1>Bazaar Advisor / Flip Feedback</h1><div class="sub">Local suggestion log · ratings and notes stay on this computer</div></div><div class="tools"><select id="filter"><option value="all">All flips</option><option value="unrated">Unrated only</option><option value="rated">Rated only</option></select><button id="refresh">Refresh</button></div></header>
<div class="summary"><span id="total">Suggestions: 0</span><span id="rated">Rated: 0</span><span id="updated">Waiting for log…</span></div>
<div id="list" class="list"><div class="empty">Waiting for Bazaar Advisor suggestions in Minecraft…</div></div>
</main><script>
const list=document.querySelector('#list'),filter=document.querySelector('#filter');
function coins(n){n=Number(n||0);if(n>=1e6)return (n/1e6).toFixed(2).replace(/0+$/,'').replace(/\.$/,'')+'m';if(n>=1e3)return (n/1e3).toFixed(1).replace(/\.0$/,'')+'k';return Math.round(n).toLocaleString()}
function esc(s){return String(s??'')}
function card(f){const article=document.createElement('article');article.className='flip'+(f.rating?' rated':'');const info=document.createElement('div');const title=document.createElement('h2');title.textContent=(f.rarity?`[${f.rarity}] `:'')+esc(f.item);info.append(title);const facts=document.createElement('div');facts.className='facts';for(const t of [`ID ${f.id}`,`Buy ${coins(f.buyPrice)}`,`Recent sale ${coins(f.realizedSalePrice)}`,`Est. net +${coins(f.estimatedProfit)}`]){const span=document.createElement('span');span.textContent=t;if(t.startsWith('Est.'))span.className='profit';facts.append(span)}info.append(facts);const form=document.createElement('div');const row=document.createElement('div');row.className='rating';const select=document.createElement('select');select.setAttribute('aria-label','Your flip rating');select.innerHTML='<option value="">Rate…</option>'+[1,2,3,4,5].map(n=>`<option value="${n}">${n} / 5</option>`).join('');if(f.rating)select.value=String(f.rating);const note=document.createElement('textarea');note.maxLength=1000;note.placeholder='Comment: accurate price? Would it sell? What modifier was missed?';note.value=esc(f.comment);const save=document.createElement('button');save.className='save';save.textContent='Save';row.append(select,note,save);const saved=document.createElement('div');saved.className='saved';if(f.rating)saved.textContent=`Saved ${f.rating}/5${f.comment?' · '+f.comment:''}`;save.addEventListener('click',async()=>{if(!select.value){saved.textContent='Choose a rating from 1 to 5.';return}save.disabled=true;try{const r=await fetch('/api/rate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:f.id,rating:Number(select.value),comment:note.value})});const data=await r.json();if(!r.ok)throw Error(data.error||'Save failed');saved.textContent='Saved locally.';await load()}catch(e){saved.textContent=e.message}finally{save.disabled=false}});form.append(row,saved);article.append(info,form);return article}
async function load(){try{const r=await fetch('/api/flips');if(!r.ok)throw Error('Could not read flip log');const d=await r.json();document.querySelector('#total').textContent=`Suggestions: ${d.total}`;document.querySelector('#rated').textContent=`Rated: ${d.rated}`;document.querySelector('#updated').textContent=`Updated ${new Date().toLocaleTimeString()} · ${d.path}`;let flips=d.flips;if(filter.value==='unrated')flips=flips.filter(x=>!x.rating);if(filter.value==='rated')flips=flips.filter(x=>x.rating);list.replaceChildren(...(flips.length?flips.map(card):[Object.assign(document.createElement('div'),{className:'empty',textContent:'No flips in this view yet.'})]))}catch(e){list.innerHTML='';const x=document.createElement('div');x.className='empty';x.textContent=e.message+' Make sure the launcher is running.';list.append(x)}}
document.querySelector('#refresh').addEventListener('click',load);filter.addEventListener('change',load);load();
</script></body></html>'''


def read_flips():
    suggestions = {}
    ratings = {}
    if LOG_PATH.exists():
        with LOCK, LOG_PATH.open("r", encoding="utf-8") as stream:
            for line in stream:
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if event.get("event") == "suggestion":
                    suggestions[event.get("id")] = event
                elif event.get("event") == "rating":
                    ratings[event.get("id")] = event
    flips = []
    for flip_id, suggestion in suggestions.items():
        rating = ratings.get(flip_id, {})
        flips.append({**suggestion, "rating": rating.get("rating"), "comment": rating.get("comment", "")})
    flips.sort(key=lambda item: item.get("time", 0), reverse=True)
    return flips, len(ratings)


class Handler(BaseHTTPRequestHandler):
    def _json(self, data, status=200):
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        route = urlparse(self.path).path
        if route == "/":
            payload = PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        elif route == "/api/flips":
            flips, rated = read_flips()
            self._json({"flips": flips, "total": len(flips), "rated": rated, "path": str(LOG_PATH)})
        else:
            self._json({"error": "Not found"}, 404)

    def do_POST(self):
        if urlparse(self.path).path != "/api/rate":
            self._json({"error": "Not found"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(length))
            flip_id = str(data.get("id", ""))
            rating = int(data.get("rating", 0))
            comment = str(data.get("comment", ""))[:1000]
            flips, _ = read_flips()
            match = next((flip for flip in flips if flip.get("id") == flip_id), None)
            if match is None:
                self._json({"error": "Unknown flip ID"}, 404)
                return
            if rating < 1 or rating > 5:
                self._json({"error": "Rating must be 1 through 5"}, 400)
                return
            event = {key: match.get(key) for key in ("id", "item", "buyPrice", "realizedSalePrice", "estimatedProfit")}
            event.update({"event": "rating", "rating": rating, "comment": comment, "time": __import__("time").time_ns() // 1_000_000})
            LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
            with LOCK, LOG_PATH.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(event, ensure_ascii=False) + "\n")
            self._json({"saved": True})
        except (ValueError, json.JSONDecodeError, OSError) as error:
            self._json({"error": str(error)}, 400)

    def log_message(self, format_string, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 8766), Handler)
    print(f"Flip feedback dashboard: http://127.0.0.1:8766")
    print(f"Reading local feedback log: {LOG_PATH}")
    print("Keep this window open; press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopping feedback dashboard.")
    finally:
        server.server_close()
