# run_mcp.py — GovTrace MCP Server
# Expõe o mcp-brasil + tool GovTrace para emendas sem limite de página única.

import config  # noqa: F401 — carrega .env e valida TRANSPARENCIA_API_KEY antes do MCP

import logging

from mcp_brasil.server import mcp

from tool import govtrace_buscar_emendas

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(levelname)s: %(message)s")

# Tool exclusiva do TCC: paginação completa de emendas (ver tool.py)
mcp.tool(govtrace_buscar_emendas, tags={"govtrace", "emendas", "orcamento", "balanceamento"})

if __name__ == "__main__":
    print("✅ GovTrace MCP Server pronto na porta 8000")
    print("📡 Tools mcp-brasil + govtrace_buscar_emendas em http://localhost:8000/mcp")
    mcp.run(transport="http", port=8000)
