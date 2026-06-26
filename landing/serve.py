import os, http.server, socketserver
os.chdir(os.path.dirname(os.path.abspath(__file__)))
PORT = int(os.environ.get('PORT', 8087))
Handler = http.server.SimpleHTTPRequestHandler
with socketserver.TCPServer(('', PORT), Handler) as httpd:
    print(f'Serving on port {PORT}')
    httpd.serve_forever()
