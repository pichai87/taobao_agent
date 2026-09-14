#!/bin/bash
set -euo pipefail
# 为避免 SQL 字面量插值风险，此脚本只接受这一组密码字符。
if [[ ! "${MYSQL_READER_PASSWORD}" =~ ^[A-Za-z0-9_-]{16,128}$ ]]; then
  echo "MYSQL_READER_PASSWORD must be 16-128 alphanumeric, underscore or dash characters" >&2
  exit 1
fi
MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --user=root <<SQL
CREATE USER IF NOT EXISTS 'agent_reader'@'%' IDENTIFIED BY '${MYSQL_READER_PASSWORD}';
SQL
# 视图由应用 Flyway 创建。之后再由管理员运行 grant-reader.sql。

