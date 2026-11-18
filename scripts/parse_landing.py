import urllib.request
import ssl
import re

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request('https://transport.mbstu.ac.bd/', headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req, context=ctx, timeout=10) as res:
    html = res.read().decode('utf-8', errors='ignore')

print("Title:", re.findall(r'<title>(.*?)</title>', html))
# All text inside headings, nav, buttons, links
links = re.findall(r'href="([^"]+)"', html)
print(f"Links ({len(links)}):")
for l in set(links):
    print(" ", l)

# Extract plain text snippets
from html.parser import HTMLParser

class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.texts = []
        self.in_script = False

    def handle_starttag(self, tag, attrs):
        if tag in ['script', 'style']:
            self.in_script = True

    def handle_endtag(self, tag):
        if tag in ['script', 'style']:
            self.in_script = False

    def handle_data(self, data):
        if not self.in_script:
            t = data.strip()
            if len(t) > 2:
                self.texts.append(t)

parser = TextExtractor()
parser.feed(html)
print(f"\nExtracted text elements: {len(parser.texts)}")
for t in parser.texts[:60]:
    try:
        print("  -", t)
    except Exception:
        pass
