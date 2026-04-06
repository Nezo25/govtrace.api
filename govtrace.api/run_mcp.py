# server.py — GovTrace MCP Server
# Simplesmente expõe o mcp-brasil como HTTP, sem wrapper desnecessário.
# As 286 tools do mcp-brasil ficam disponíveis direto em http://localhost:8000/mcp

from mcp_brasil.server import mcp

if __name__ == "__main__":
    print("✅ GovTrace MCP Server pronto na porta 8000")
    print("📡 286 tools do mcp-brasil expostas em http://localhost:8000/mcp")
    mcp.run(transport='http', port=8000)