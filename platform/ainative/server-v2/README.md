# AINative Server v2(重写基线)

Spring Boot 3.3 / JDK 21 / seekdb。与 v1(platform/ainative)并行运行,逐域重写、
按域切换。当前进度:**身份域垂直切片已打通**。

## 结构
```
src/main/java/com/ainative/v2/
  Application.java         启动类
  common/                  响应包络(与 v1 兼容:{success,code,message,data})/业务异常/全局处理
  auth/                    身份域:UserDao(JDBC)/AuthService/JwtService(HS512 零依赖)/AuthController
```

## 运行
```bash
docker build -f Dockerfile.v2 -t ainative-server-v2:dev .
docker run -d --name ainative-v2-dev --network ainative-community -p 7011:7011 \
  -e DB_HOST=host.docker.internal -e DB_PORT=2891 \
  -e AINATIVE_JWT_SECRET=<secret> ainative-server-v2:dev
```

## 已实现契约(与 v1 对齐)
- POST /api/auth/register {username,password,nickname,email}
- POST /api/auth/login {username,password} → {access_token,username,role}
- GET  /api/auth/me (Bearer) → 当前用户
- 错误码沿用:10001 参数不合法 / 10401 未登录或凭据错误 / 10500 内部错误

## 数据
seekdb `ainative_v2` 库(10 域 106 表,schema v1 见 ../docs/seekdb-schema-v1.md)。

## 验证记录(2026-11-12)
注册/登录/me 闭环通过;错误密码与无令牌返回 10401;id_user 落库正确。
