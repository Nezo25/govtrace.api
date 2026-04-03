from fastmcp import FastMCP
from mcp_brasil import server as mcp_oficial

# Criamos o seu Hub que será o servidor HTTP (porta 8000)
mcp = FastMCP("GovTrace-Hub")

@mcp.tool(name="tce_sp")
async def tce_sp(municipio: str, ano: int, mes: int = 1):
    """Aciona o robô real do mcp-brasil via Dispatcher"""
    print(f"🚀 [GovTrace] Chamando minerador real para {municipio}...")
    # Chamada de baixo nível ao servidor que já tem as ferramentas mapeadas
    result = await mcp_oficial.mcp.call_tool("tce_sp", {
        "municipio": municipio,
        "ano": ano,
        "mes": mes
    })
    return result

@mcp.tool(name="brasilapi_cnpj")
async def brasilapi_cnpj(cnpj: str):
    """Aciona o consulta CNPJ real via Dispatcher"""
    print(f"🔍 [GovTrace] Consultando CNPJ real: {cnpj}")
    result = await mcp_oficial.mcp.call_tool("brasilapi_cnpj", {"cnpj": cnpj})
    return result

if __name__ == "__main__":
    print("✅ Servidor GovTrace pronto na porta 8000")
    print("📡 Modo: Dados Reais (Dispatcher Bridge)")
    mcp.run(transport='http', port=8000)