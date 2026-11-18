import urllib.request
import ssl
from html.parser import HTMLParser

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

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
            if t:
                self.texts.append(t)

for page in ['/Schedule', '/Vehicles', '/Transport-Directory', '/Notices-News']:
    req = urllib.request.Request('https://transport.mbstu.ac.bd' + page, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, context=ctx, timeout=10) as res:
        html = res.read().decode('utf-8', errors='ignore')
    parser = TextExtractor()
    parser.feed(html)
    print(f"\n================= {page} =================")
    print("Texts count:", len(parser.texts))
    for t in parser.texts[:50]:
        print(" ", t)
