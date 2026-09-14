# MCP 边界（尚未实现协议服务）

MCP = Model Context Protocol（模型上下文协议），用于把工具提供给其他 Agent 客户端。

模块已在 Maven 主工程中占位，但当前没有监听端点、没有协议协商，不宣称 MCP 已接通。
下一批工作使用官方 Java SDK 实现受认证的 Streamable HTTP：
仅提供 metric_definitions、get_owned_report 两个只读工具。
调用者身份必须来自服务端认证，不能接受客户端传入 owner 作为授权依据。
上线前必须验证 initialize、tools/list、tools/call、错误响应、跨用户拒绝及 Origin 校验。

禁止把普通 REST 接口包装成 JSON 就宣称完整 MCP。

