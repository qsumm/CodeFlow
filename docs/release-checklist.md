# 发布检查清单

> 基线:`779eda5`(v1.0 清单基线,仅供对照)· 发布时改写为本次发布 commit · 版本:v1.9(2026-11-04 工单 #10016 增补回滚章)
> 适用:CodeFlow 平台(Java + 前端)与执行器(runtime)· 用法:发布前**逐条执行并勾选**,任一条不通过即停止发布
> **回滚入口**:上线判定不过 / 上线后发现严重缺陷 ⇒ 直接进 **§4 回滚**(触发与决策 → 前置检查 → 执行 →
> 数据层 → 验证 → 遗留动作 → 速查表),先读 §4-①② 再动手;能否回滚取决于 §1-③ 的 `SAVE_STATUS`
> 执行结果随发布说明留档(取档命令见 §2-③)。上游发布验证契约:`platform/ainative/e2e-tests/AGENT_GUIDE.md`
> (`verify.sh` 是唯一编排入口,本清单不自造 E2E 口径)。

**前置**(逐条确认,缺一即无法发布):

- 宿主常备:`docker`,以及 **Compose ≥ v2**(须支持 `up -d --wait`;**v1 老二进制不满足**)
- **`docker compose` 这个命令名必须可用**:独立二进制 `docker-compose` 自 v2 起**能力等价**
  (实测 v5.1.0 的 `up --help` 含 `--wait`),但 Docker CLI **不会**把 PATH 上的 `docker-compose` 当插件
  ⇒ 二选一:①装官方 Compose 插件;②把 v2+ 独立二进制放到 `~/.docker/cli-plugins/docker-compose`
  (使其成为插件)。**只装了独立二进制的机器不是「缺件」,只是命令名不满足**
- **闸门**另需 Docker Buildx(闸门用 `docker build --platform linux/amd64`);
  **交付路径只需 docker + Compose v2,不依赖 Buildx**
- 另需:git/curl/openssl、python3 —— 闸门只要求**存在**(`python3 --version`),无版本下限
- **§2-① runtime 单测**另需 Python ≥3.11(`pyproject.toml` 的 `requires-python`)
- **前端构建**另需 Node ≥20 与 npm(§1-④ 与 `FULL_BUILD=1` 用;仓库未固定 `engines`);
  闸门用自带的 `target/node/node`,**不依赖宿主 Node**
- **闸门**另需宿主 **JDK 21 + Maven**(闸门共 9 项宿主前置);缺 JDK 21 时它会尝试自动安装,受网络限制的机器先备好
- **闸门**在宿主跑 `mvn clean verify` 且**未带 `-DskipFrontend`** ⇒ 宿主还需可达
  **`nodejs.org`**(下载 Node v22.22.2)与 **`registry.npmjs.org`**(`npm ci`);
  交付构建用 npmmirror 镜像源,**闸门直连 npmjs**,两者网络要求不同
- 依赖预检(在 `platform/ainative/` 下):`./e2e-tests/verify.sh --start --no-install --project-root "$(pwd -P)"`
  → `--no-install` 关闭**全部 9 项宿主前置**的自动安装(**不只 JDK**),且**在第一个缺件处即中止**
    ⇒ 缺件需反复预检才能列全
  → 它**仍会走完启动流程**:前置齐备时会真的构建镜像并拉起整个环境
  → 缺件时以 `VERDICT=FAIL / FAILURE_KIND=INSTALL_DISABLED` 报出
  → **预检会留下 run 指针** ⇒ 跑完**必须**紧跟清理:
    `./e2e-tests/verify.sh --clean-up --project-root "$(pwd -P)"`
- **`--stop` 只停容器、不删 run 指针**;解阻断**只能**用 `--clean-up`
- 构建镜像源须可达:`maven:3.9.9-eclipse-temurin-21`、`eclipse-temurin:21-jre-jammy`
- 运行镜像源须可达:`mysql:8.0`、`redis:7-alpine`、`minio/minio:latest`、`quay.io/minio/minio:latest`
- 闸门镜像源另需:`quay.io/minio/mc:latest`(建桶用);`python:3.12-alpine` **仅 `--with-sls` 需要**
- 宿主 `~/.m2` 须预热或保证可联网:闸门跑的是宿主 `mvn clean verify`,**不走** `ainative-m2-cache` 卷

## 1. 构建

- [ ] **基线与工作区**
  - 命令:`git log -1 --oneline`;`git status --porcelain`
  - 判据:发布 commit 与发布说明一致;`git status` 只含已登记的未跟踪产物
  - 注意:仓库无 CI、无 tag ⇒ **发布 commit 就是版本锚点**,须写进发布说明
  - **发布动作第一步**:给当前镜像与 jar 留存可追溯副本
    (**必须在 §1-③ 构建之前做**,命令见 §1-③ 首行)

- [ ] **版本号一致**
  - 命令:比对 `platform/ainative/VERSION`、`pyproject.toml`、`platform/ainative/frontend/VERSION`
  - 另一处:`platform/ainative/src/main/resources/application.yml` 的
    `ainative.runtime.recommended-version`
  - 判据:以哪一处为准由人工裁决,结论与所选版本号记入发布说明
  - 注意:**不要**把 `platform/runtime.env` 的 `AINATIVE_VERSION` 当版本源 —— 该文件由
    `bootstrap.sh` 硬编码生成(`1.0.0`)且不入库;若它已存在,只需人工确认其与本次发布一致
  - 参考:v1.0 基线实测 5 处不一致(1.1.0/0.1.0/0.6.0/1.0.0/0.3.30)—— 仅供对照,不作判据

- [ ] **构建运行镜像**
  - **发布动作第一步(在构建之前做)——留存上一版镜像与 jar**:

    > **本段必须在仓库根执行**(下面用的是**仓库根相对**路径:`platform/ainative/target/ainative.jar`)。
    > 在别的目录执行 ⇒ `cp` 找不到文件 ⇒ **jar 静默没留存**(实测),而文案会把失败解释成「无上一版」
    > ⇒ 照做即丢掉回滚素材。块内已加守卫:**两个留存动作各自独立执行、分别报告**,
    > 一个失败**不短路**另一个;`SAVED_IMAGE_TAG=` / `SAVED_JAR=` **只在该项成功时打印**。

    ```bash
    # 执行前先把这 3 个变量的尖括号内容替换成实际值(尖括号在 shell 里是重定向符,不替换直接语法报错)
    PREV_COMMIT="<上一版 commit>"        # 上一版 commit 的短 sha,例:779eda5
    RELEASE_TAG="<日期>-<上一版 commit>"  # 留存标签的后缀,例:20261104-779eda5
    SAVE_DIR="<留存目录>"                 # 实际留存目录,例:/var/backups/ainative
    case "$PREV_COMMIT$RELEASE_TAG$SAVE_DIR" in
      *"<"*) echo "STOP: 占位符未替换 —— 请先填 PREV_COMMIT / RELEASE_TAG / SAVE_DIR" >&2 ;;
      *)
        mkdir -p "$SAVE_DIR"
        # 两个留存动作互相独立:一个失败**不短路**另一个(短路会互相掩盖,实测)
        IMG_OK=0; JAR_OK=0
        if docker tag ainative-community:local "ainative-community:${RELEASE_TAG}"; then
          IMG_OK=1
          echo "SAVED_IMAGE_TAG=ainative-community:${RELEASE_TAG}"   # §4-③ 的 OLD_IMG 直接复制这个值
        else
          echo "STOP: 镜像留存失败(没有 ainative-community:local 这个 tag?docker 本身可用?)" >&2
        fi
        if cp platform/ainative/target/ainative.jar "${SAVE_DIR}/ainative-${PREV_COMMIT}.jar"; then
          JAR_OK=1
          echo "SAVED_JAR=${SAVE_DIR}/ainative-${PREV_COMMIT}.jar"
        else
          echo "STOP: jar 留存失败(是否在仓库根执行?platform/ainative/target/ainative.jar 是否存在?)" >&2
        fi
        # 汇总:只有两项**皆无**才是「无上一版」;只成功一项 = 部分留存,已成功的那项仍然有效
        if [ "$IMG_OK" -eq 1 ] && [ "$JAR_OK" -eq 1 ]; then
          echo "SAVE_STATUS=BOTH"
        elif [ "$IMG_OK" -eq 1 ] || [ "$JAR_OK" -eq 1 ]; then
          echo "SAVE_STATUS=PARTIAL —— 已成功的留存项仍然有效(见上面 SAVED_* 行),**不要**当成「无上一版」"
        else
          echo "SAVE_STATUS=NONE"
        fi
        ;;
    esac
    ```

  - 留存的文件名约定:jar 落在 `${SAVE_DIR}/ainative-<上一版 commit>.jar`(用实际 commit 短 sha 命名)
  - **每一项**留存成功**各自打印一行**(`SAVED_IMAGE_TAG=…` / `SAVED_JAR=…`)⇒ 把 `SAVED_IMAGE_TAG` 的值
    **原样抄到 §4-③ 的 `OLD_IMG`**(标签带日期前缀,别自行拼成 `ainative-community:<上一版 commit>`)
  - 块末打印 `SAVE_STATUS=BOTH / PARTIAL / NONE` 汇总行,对应**留存项的成败组合**(两项全成 / 只有一项成 / 两项皆无)
  - **占位符没替换时,上面那段只会打印 STOP、不会留存任何产物** ⇒ 看到 STOP 先填值再执行,
    **不要**把它当成「无上一版」而跳过(跳过 = 回滚路径丢失)
  - **只有 `SAVE_STATUS=NONE`(两项皆无)才是「无上一版」**:首次发布 / 干净发布机时才会这样
    (无 `ainative-community:local` 这个 tag ⇒ `No such image`;无上一版 jar ⇒ `cp` 失败)
    ⇒ 此时才**跳过本步**,并在发布说明登记「**本次发布无上一版产物,回滚路径不可用**」
  - **`SAVE_STATUS=PARTIAL` 不是「无上一版」**:只成功了一项(常见:镜像在、jar 被 §2-② 的 `clean` 删了)
    ⇒ 已成功的那项**照常登记并使用**(例如 `SAVED_IMAGE_TAG` 照样抄进 §4-③ 的 `OLD_IMG`),
    只把失败的那项按对应 STOP 文案处置;**不得**因为另一项失败就把已留存的那项一并写成「回滚路径不可用」
    (这会把可用的回滚素材误报为丢失)
  - 理由:仓库无 tag;`build.sh` 不清 `target/` 且**直接覆盖** `ainative-community:local` 这个 tag
    ⇒ 不留存的话旧镜像只剩无标签的悬空层,旧 jar 还会被 §2-② 的 `clean` 删除
  - **再清理再构建**(在仓库根):`rm -rf platform/ainative/target/classes/static`
  - 理由:`build.sh` 用 `mkdir -p` + `cp -R` 往 `target/classes/static` **逐次累积**,**全程不清 `target/`**
    ⇒ 不清理会把仓库里已不存在的旧前端 chunk 打进交付镜像,旧 URL 仍可被请求
  - 命令:`cd platform && ./build.sh`(默认 REUSE_DIST,复用随库 `frontend/dist`,约 3 分钟)
  - 从源码重编前端:`FULL_BUILD=1 ./build.sh`(需容器可达 npmmirror,约 15–25 分钟)
  - 判据:退出码 0,产出待交付镜像 `ainative-community:local`
  - 注意:构建带 `-DskipTests` ⇒ **构建绿 ≠ 测试绿**,测试须另跑(§2)
  - 注意:构建上下文为 `platform/`,须存在 `platform/.dockerignore`
    ⇒ 否则 `platform/runtime.env`(含 master key)会被送进 Docker 构建上下文
  - 交付镜像冒烟(证交付产物本身能起,闸门不覆盖它):`cd platform && ./bootstrap.sh`
  - 判据:`http://localhost:7001/checkpreload.htm` 返回 200;冒烟用 `--env-file platform/runtime.env`
  - 注意:`platform/runtime.env` 缺失时 `bootstrap.sh` 会生成新密钥 ⇒ 先确认已存在
  - **前端探针**(闸门验的是另一个镜像,永远验不到这一层,只有交付镜像冒烟能证):
    设 `BASE=http://localhost:7001`(走下面的备用 `docker run` 路径时为 `7002`)

    ```bash
    set -e
    code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
    [ "$code" = "200" ] || { echo "FAIL 根路径非 200: $code"; exit 1; }
    curl -s "$BASE/" | grep -q '<script' || { echo "FAIL 入口页不含 <script>"; exit 1; }
    asset="$(curl -s "$BASE/" | grep -o '/assets/[^"]*\.js' | head -1)"
    [ -n "$asset" ] || { echo "FAIL 未取到 JS 资源名 —— 前端探针失败"; exit 1; }
    curl -s -o /dev/null -w '%{http_code}\n' "$BASE$asset" | grep -qx 200 \
      || { echo "FAIL JS 资源不可达: $asset"; exit 1; }
    echo "PROBE_OK asset=$asset"
    ```

  - 探针**必须 fail-closed**:取不到 JS 资源名就**直接失败** ⇒ 防止"前端整站被换成兜底页/维护页"
    却因根路径仍是 200 而误判通过
  - 资产名带内容哈希、每次重建都会变 ⇒ 必须**从 `index.html` 取**,不可写死文件名
  - 宿主 `docker compose` **命令不可用**时(见前置:能力与命令名是两条独立要求)用**等价的服务启动**冒烟
    (**前端探针仍须按上面执行**;
    **仅适用于依赖服务已由其他方式起好**:该命令不可用时 `bootstrap.sh` 会在 `[1/4]` 中止、
    `ainative-community` 网络也建不出来;**以下命令在仓库根执行**,`--env-file` 是仓库根相对路径;
    冒完删容器):

    ```bash
    docker run -d --name ainative-rc-smoke --network ainative-community \
      --env-file platform/runtime.env -e JAVA_OPTS="-Xmx2g -Xms512m" \
      -p 7002:7001 ainative-community:local
    curl -s -o /dev/null -w '%{http_code}' http://localhost:7002/checkpreload.htm  # 期望 200
    docker rm -f ainative-rc-smoke
    ```

    然后把上面那段前端探针按 `BASE=http://localhost:7002` **再跑一遍**

- [ ] **前端产物完整**
  - 命令(**在仓库根执行**):`cd platform/ainative/frontend && npm ci` → `npm run build`
  - `npm ci` 需 `registry.npmjs.org` 可达(`node_modules` 不入库,干净发布机上必然缺失)
  - 判据:退出码 0,输出含 `i18n resource pairs and UI literals checked.`
  - 注意:`npm run build` 会**重写随库入库的 `platform/ainative/frontend/dist`**
  - 在仓库根执行 `git status --porcelain -- platform/ainative/frontend/dist`,应为空
  - **内容级判据(交付镜像内静态资源 == 入库 dist)**:在仓库根执行下面 4 行,`diff` **应为空**

    ```bash
    python3 -m zipfile -l platform/ainative/target/ainative.jar \
      | tail -n +2 | awk '{print $1}' | grep '^BOOT-INF/classes/static/' | grep -v '/$' \
      | sed 's#^BOOT-INF/classes/static/##' | sort > /tmp/jar-static.txt
    git ls-files platform/ainative/frontend/dist \
      | sed 's#^platform/ainative/frontend/dist/##' | sort > /tmp/repo-dist.txt
    diff /tmp/jar-static.txt /tmp/repo-dist.txt && echo DIST_MATCH
    ```

  - 上面的 `python3 -m zipfile` 是**零新增依赖**写法(python3 本就是宿主前置);
    也可用 `unzip -Z1 platform/ainative/target/ainative.jar 'BOOT-INF/classes/static/*'`
  - **先断言、再比对**(否则失败原因会被误导向一次无意义的重建):

    ```bash
    ls platform/ainative/target/ainative.jar >/dev/null 2>&1 \
      || { echo "FAIL jar 不存在 —— 先跑 §1-③ 的构建,不是前端问题"; exit 1; }
    command -v python3 >/dev/null 2>&1 \
      || { echo "FAIL 缺少 python3 —— 无法列 jar 内容,不是前端问题"; exit 1; }
    ```

  - `diff` 非空时按**三型分诊**(别一律重建):
    - **jar 不存在** ⇒ 回 §1-③ 构建(不是前端问题)
    - **列 jar 的工具缺失** ⇒ 补齐工具后重跑(不是前端问题)
    - **真的不一致**(jar 在、工具在、仍非空)⇒ 回 §1-③ **先清理再构建**
  - 工作区漂移(`git status` 非空)二选一:
    - **提交**重建的 dist ⇒ **必须重跑 §1-③ 重建镜像并重跑冒烟**(否则镜像里仍是旧 dist)
    - **还原**(`git checkout -- platform/ainative/frontend/dist`)⇒ **工作区漂移消失**
      (实测:`git status --porcelain -- platform/ainative/frontend/dist` 变空、文件内容回到入库版本);
      但**已构建的 jar / 镜像里可能仍是重建过的 dist** ⇒ **必须重跑本条内容级比对**再定论:
      打印 `DIST_MATCH` ⇒ 绿灯;`diff` 非空 ⇒ 重跑 §1-③ **先清理再构建**后再次比对。
      未比对就交付 ⇒ 镜像内静态资源可能与入库 dist 不一致,**不得当作绿灯**
  - 参考:v1.0 基线实测入库 89 个文件、重建后变更 0 个 —— 仅供对照,不作判据

## 2. 测试

- [ ] **runtime 单测**
  - 命令(**在仓库根执行**):`python3 -m pytest "$(git rev-parse --show-toplevel)/tests/" -q`
  - 判据:退出码 0,输出无 `failed`/`error`
  - 注意:**必须用绝对路径**:仓库内有 3 个 `tests/`(根 `tests/`、`platform/ainative/e2e-tests/tests`、
    `platform/ainative/scripts/tests`),相对路径 `tests/` 会随工作目录收走**闸门自身的测试套件**而静默假绿
  - 套件身份判据(确认收的是运行期套件而非闸门套件):

    ```bash
    python3 -m pytest "$(git rev-parse --show-toplevel)/tests/" -q --collect-only \
      | grep -q 'test_chain.py' || { echo "FAIL 收集到的不是运行期套件"; exit 1; }
    ```

  - 注意:裸 `pytest` 会额外收集 `platform/` 与 `runtime-data*/` 的历史工作区副本
    (实测多收 93 例,共 144 例),结果失真
  - 参考:v1.0 基线实测 51 passed —— 仅供对照,不作判据

- [ ] **平台 Java 全量**
  - 判据:`BUILD SUCCESS` 且 `Failures: 0, Errors: 0`,退出码 0
  - 前置:先跑 §1-③ 的构建命令(仓库根下的 `platform/build.sh`)生成 `platform/settings.xml`(该文件不入库)
  - 缺失时 Docker 会把挂载源自动建成目录,Maven 直接失败,并弄脏工作区
  - 注意:`-o` 为离线模式,冷缓存须先联网预热 `ainative-m2-cache`;约 2–5 分钟
  - 参考:v1.0 基线实测 7607 通过 / 19 跳过;本次带 `clean` 实测 7611 通过 / 19 跳过
    (含工作区**未入库**的并发 WIP 测试类 4 例)—— 均仅供对照,不作判据
  - 命令(**必须带 `clean`**;**在仓库根执行** —— 下面两处 `$PWD` 都依赖仓库根):

    ```bash
    docker run --rm -v "$PWD/platform/ainative":/workspace -w /workspace \
      -v ainative-m2-cache:/root/.m2 \
      -v "$PWD/platform/settings.xml":/root/.m2/settings.xml:ro \
      maven:3.9.9-eclipse-temurin-21 \
      mvn -B -ntp -o -DskipGitCommitId=true -DskipFrontend=true clean test
    ```

  - 注意(**内存紧张宿主上进程会被杀**):上面的原文命令在容器可用内存不足时,surefire 派生的
    测试 JVM 会被 **OOM 杀掉** ⇒ 输出 `Process Exit Code: 137` +
    `The forked VM terminated without properly saying goodbye` + `Crashed tests: …`
    (dump / dumpstream 落在 `platform/ainative/target/surefire-reports/`)——
    这是**进程被杀**、**不是用例失败**,别按「有红」去排查用例
    ⇒ 处置:加大堆上限后**重跑**(判据口径不变,仍是 `BUILD SUCCESS` + `Failures: 0, Errors: 0` + 退出码 0),
      即在 §2-② 原文命令的 `docker run …` 前缀下追加以下 JVM 参数后重跑:

    ```bash
    mvn -B -ntp -o -DskipGitCommitId=true -DskipFrontend=true \
      -DargLine=-Xmx768m -Djacoco.skip=true -DforkCount=1 -DreuseForks=true clean test
    ```

    ⇒ **重跑成功的日志必须与命令一并留档**(命令与日志对不上 ⇒ 本条无从核销)

  - 注意:工作区的 `target/` 是**挂载**进容器的,可能被残留产物或并发构建改写成半成品
    ⇒ 实测不带 `clean` 的一次跑出现 4 个 `NoClassDefFoundError`(合成内部类在 `target/test-classes`
    里缺失)假红;带 `clean` 后 **0 失败 0 错误**
  - 注意:`clean` 会删掉 `target/`(含 §1-④ 比对用的 jar)⇒ **先跑完 §1-④ 再跑本条**

- [ ] **发布验证闸门(发布判定项)**
  - 以下命令在 `platform/ainative` 下执行
  - **跑前**确认无遗留 run:`./e2e-tests/verify.sh --status --project-root "$(pwd -P)"`
    - **退出码 1 + 消息含 `FATAL: no current E2E run`** ⇒ **无**遗留 run
      ⇒ 这是 `--status` 的**正常返回**(源码即用 `FATAL` 措辞),**可继续**
    - 输出含 **`RUN_ID=`** ⇒ **有**遗留 run ⇒ 先
      `./e2e-tests/verify.sh --clean-up --project-root "$(pwd -P)"` 再重跑本项
    - 注意:`--start` 见到遗留指针会直接 `FATAL: current E2E run already exists`(**退出码 1**)退出
      ⇒ 它与上面 `--status` 的退出码**相同(都是 1)、含义相反**,别混
  - 起来:`./e2e-tests/verify.sh --start --project-root "$(pwd -P)" --keep-on-failure`
  - 判定:`./e2e-tests/verify.sh --check --project-root "$(pwd -P)"` → **三条同时满足**才算通过:
    ① **退出码 0**;② 输出含 **`VERDICT=PASS`**;③ 输出里 `RESULT_JSON=` 指向的文件中
    `"verdict": "PASS"`
  - 取法(把 `RESULT_JSON=` 后面的路径填进引号里、作为**参数**传给它 —— 写成 `<result.json>` 会被
    shell 当成**输入重定向**,脚本拿不到 `sys.argv[1]` ⇒ 必报 `IndexError`):
    `python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["verdict"])' "<RESULT_JSON 路径>"`
  - 非零退出、`VERDICT=FAIL` 或 `result.json` 的 verdict 非 `PASS` **一律不得报告为发布通过**
  - 注意:`LIFECYCLE|END|PASS` 是 **`--start` 的启动完成标志**(`verdict=STARTED`),
    **`--check` 不产生该事件** ⇒ **不得拿它当 `--check` 判据**
  - **闸门跑完(含失败重跑)必须收尾**:`./e2e-tests/verify.sh --clean-up --project-root "$(pwd -P)"`
    ⇒ 不清理会残留容器/卷/网络/端口,并让下一次 `--start` 直接 FATAL
  - **收尾同时产出留档**:`--clean-up` 把证据归档到 `platform/ainative/target/e2e-results/<runId>/`
    ⇒ **该目录内容随发布说明留档**(取档路径打印在 `EVIDENCE_ARCHIVE=`)
  - 若需保留现场做人工排查:先 `--stop`(只停容器、**不删 run 指针**),并在发布说明中**登记 runId**
  - **闸门跑完后复查工作区**(闸门会重写 `frontend/dist`):
    `git status --porcelain -- platform/ainative/frontend/dist` 期望为空;
    非空须按 §1-④ 的漂移两分支处置
  - 要连真实执行器一起验:`--with-runtime` **只能追加在 `--check` 那条命令上**(加在 `--start` 上会 FATAL 退出)
    前置:已注入 `QODER_PERSONAL_ACCESS_TOKEN` 且 `qodercli` 在 PATH(**不得打印/落盘**)
  - 注意:闸门验的是 `ainative-community-e2e:<runId>`(镜像内**现场重编前端**)
  - 它**不是**待交付镜像 `ainative-community:local`(内含**入库 `frontend/dist`**)
  - ⇒ 闸门 PASS **不等于**交付镜像的前端已验证:交付镜像冒烟与前端探针见 §1-③,
    镜像内静态资源与入库 dist 的一致性以 §1-④ 的内容级判据为准
  - 可选补(**在仓库根执行**):`python3 scripts/ui_regression_manual.py`(UI 回归,以本次实跑为准)
    —— 该脚本在**仓库根** `scripts/` 下;**§2-③ 其余命令在 `platform/ainative` 下,这一条不在那里**
    (照抄会解析成 `platform/ainative/scripts/ui_regression_manual.py`,该文件**不存在**)
    前置:`AINATIVE_BASE_URL` / `AINATIVE_ADMIN_USER` / `AINATIVE_ADMIN_PASSWORD` 三个环境变量
    + `playwright`;**`AINATIVE_BASE_URL` 的端口要与本次实际应用端口一致**
    (闸门端口被占用时会改派,见 `e2e-tests/lib/common.sh:347`)

## 3. 上线

> 上线 = 起栈 + 加固 + 备份 + 迁移 + 判定;判定不过 ⇒ 进 **§4 回滚**(回滚的权威章节,本章不重复其步骤)。

- [ ] **发布前备份 + 回滚素材留存**
  - 命令:平台「立即备份到云端」(`POST /api/workspaces/current/backups`)并确认成功
  - 知悉:该备份**不含**工单/评论/派发/会话/产物/审计/用量,密钥字段导出为 null(保留 `credential_ref`);
    平台**无 restore 接口**(只有创建/列表/下载三个端点)⇒ 恢复配置要人工导入
  - **业务数据的回退必须由下一条的处置预案覆盖**(备份不含业务表)
  - 同时留存上一版镜像与 jar:**见 §1-③ 首行,必须在构建前做**(仓库无 tag)
    ⇒ 若已执行 §1-③,旧镜像只能从**悬空镜像**(`docker images -f dangling=true`)中**人工辨认**,**不作为可靠来源**
  - 上线用交付镜像起栈:`cd platform && ./bootstrap.sh`(同 §1-③ 冒烟路径),起后确认 `/checkpreload.htm` 返回 200
  - **上线前必须先加固默认栈**(默认口令 + 全网卡暴露)—— **口令有两处载体,只改一处会静默失效**:
    - **载体① `platform/docker-compose.yml`**:`MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD`
      (默认 `change-me` / `change-root-me`);**Redis 口令当前完全没有设置**(compose 无 `requirepass`),
      要设口令须给 redis 服务加 `command: redis-server --requirepass <口令>`
    - **载体② `platform/runtime.env`**:`SPRING_DATASOURCE_PASSWORD` / `REDIS_PASSWORD`
      (应用只读这两个变量,见 `application-local.yml:26` / `:37`;由 `bootstrap.sh:16,19` 生成、经 `--env-file` 注入)
      ⇒ 该文件**仅在缺失时**由 `bootstrap.sh` 生成,内容含默认口令 `change-me` / `minioadmin`
      ⇒ 改完后**不要删掉重建**,删了会把默认口令带回来;该文件不入库,加固值须随部署机留存
    - **已有数据卷时改 `environment` 不会改掉库里的口令**:mysql:8.0 的具名卷 `mysql-data` 一旦初始化,
      `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` **只在首次初始化时生效**;改 compose **不会**改掉库里口令
      (实测:同一卷先以 `change-me` 初始化、再以新口令重建容器 ⇒ 旧口令仍可连、新口令 `ERROR 1045 Access denied`)
    - **不丢数据的改法**:在库内 `ALTER USER 'ainative'@'%' IDENTIFIED BY '<新口令>';`(root 同理),
      或按 §3-② 的**转储件重建卷**;改完**同步载体①与载体②** ——
      只改载体② ⇒ 应用拿新口令去连旧口令的库 ⇒ 连接被拒,且报错不会指向「你刚改的口令」
    - **自证判据**(两条都满足才算通过):新 root 口令 `mysql -h127.0.0.1 -P33060 -uroot -p'<新口令>' -e 'select 1'`
      成功 **且** 旧口令 `change-me` 连接失败
      - **宿主没有 mysql 客户端也能验**(发布机不一定装了):用容器内的客户端,命令形态相同 ——
        `docker exec platform-mysql-1 mysql -uroot -p'<新口令>' -e 'select 1'`(应成功)、
        `docker exec platform-mysql-1 mysql -uroot -p'change-me' -e 'select 1'`(应报 `ERROR 1045`)
        (容器名 = compose 默认的 `<项目目录>-<服务>-1`,项目目录即 `platform/`)
    - **MinIO 也要加固**(默认口令 + 全网卡暴露,**项目备份包就存在它里面**):
      改 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`(默认 `minioadmin` / `minioadmin`)、把 `9000` / `9001`
      绑到 `127.0.0.1:…`、并把新的 `S3_ACCESS_KEY_ID` / `S3_ACCESS_KEY_SECRET` 同步进 `platform/runtime.env`
      (容器读 env,不读 compose 的 MinIO 配置);自证判据:新凭据能列举桶 **且** 旧凭据 `minioadmin` 被拒。
      **与 MySQL 不同**:MinIO 根凭据每次启动从 env 读取,**已初始化的 `minio-data` 卷上改 env 即生效**
      (实测:同卷换凭据重起 ⇒ 新凭据可列举、旧凭据报 `InvalidAccessKeyId`),**不需要** ALTER 类手段
      - **宿主没有 mc 客户端也能验**:MinIO 容器自带 `mc`,但镜像里的 `local` 别名**没有凭据**
        (实测:`mc alias list` 里 local 的 AccessKey/SecretKey 为空 ⇒ `mc ls local` 直接 Access Denied;
        `mc ready local` 只探活、不验口令 ⇒ compose 的 minio healthcheck 也不受改凭据影响)——
        所以要在容器内用**当前 env 的凭据**自己建别名:

        ```bash
        # 判据①:新凭据能列举桶(当前无桶时输出为空、退出码 0)
        docker exec platform-minio-1 sh -c 'mc alias set cur http://127.0.0.1:9000 \
          "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" && mc ls cur'
        # 判据②:旧凭据被拒(应报 Access Key Id … does not exist in our records,退出码非 0)
        docker exec platform-minio-1 sh -c \
          'mc alias set old http://127.0.0.1:9000 minioadmin minioadmin'
        ```
    - 把 MySQL / Redis / MinIO 端口绑到 `127.0.0.1:…`(如 `127.0.0.1:33060:3306`),不要绑所有网卡
    - 顺带一提:compose 的 healthcheck(`docker-compose.yml:17`)里也写着默认口令 `-pchange-root-me`,
      但**改口令不会让它变红**(实测:`mysqladmin ping` 在被拒时仍返回 0,容器照常 healthy ⇒
      `bootstrap.sh` 的 `docker compose up -d --wait` 不会被卡住)—— 只是探针从此不再验证 root 口令;
      想让它跟着 env 走可改成 `mysqladmin ping -h localhost -uroot -p$$MYSQL_ROOT_PASSWORD`(`$$` 取容器内环境变量)

- [ ] **数据库迁移只进不退**
  - 新迁移必须是严格递增的 `V<n>__<desc>.sql`;**已发布分支的迁移文件禁止修改、改名、删除**
  - **迁移目录 = `platform/ainative/docs/migration/`**;命名契约(`V` 大写、编号严格递增、
    发布后禁止改名删除、禁止放 rollback SQL / 全量快照 / 凭据)见同目录 `README.md`
  - 迁移目录**禁止放 rollback SQL** ⇒ 平台不提供 down 迁移 ⇒ 发布前先确认"若要回退,数据层如何处置"
  - **数据层处置预案(可执行要求)**:发布前须**产出并留存业务表转储件**
    (转储命令 + 文件路径 + 校验和),并写明恢复验证方式;
    **没有转储件即视为数据层不可回退**,必须在发布说明中登记为已知风险
  - **迁移演练**:在**有数据的副本**上按 `V<n>` 升序执行新迁移并记录 checksum,
    判据 = **全部新版本执行成功 + 应用能起 + 关键查询可用**
  - 注意:**闸门不覆盖迁移** —— 平台无迁移执行器(无 Flyway/Liquibase),
    闸门在**空卷**上导入**全量快照** ⇒ 验的是**新装**不是**升级**;迁移由平台外的部署流程执行
  - 迁移后须人工重做:重新绑定凭据 → 重设机器人回调密钥 → **重新签发执行器令牌**

- [ ] **上线后判定与回滚入口**
  - 上线成功判据(**两条都要过**,都在**交付镜像**上验):`/checkpreload.htm` 返回 200;
    §1-③ 的**前端探针** 4 步全过(`PROBE_OK`)
  - **任一判据不过 ⇒ 停止上线流程,进入 §4 回滚**;§4 是回滚的**唯一权威章节**
    (触发与决策 → 前置检查 → 执行步骤 → 数据层处置 → 回滚后验证 → 遗留动作 → 速查表)
  - **能否回滚取决于 §1-③ 留存块的 `SAVE_STATUS`**(发布时已打印):
    - `SAVE_STATUS=BOTH` / `PARTIAL` ⇒ 上一版产物至少一项在位 ⇒ **可按 §4 回滚**
      (`PARTIAL` 下已成功的那项照常使用,见 §1-③ 的分项说明)
    - `SAVE_STATUS=NONE` ⇒ **本次发布无上一版产物,回滚路径不可用** ⇒ 只能**向前修复**
      (定位缺陷 → 热修 → 重走 §1 构建 / §2 测试 → 重新上线),**不得**执行 §4
  - 发布说明登记上线判定结果;判定进入回滚的,另按 §4-⑥ 登记回滚事件

## 4. 回滚(发布回退 Runbook)

> 本章是回滚的**唯一权威章节**:由 v1.8 的 §3-③「回滚执行与验证」整体迁入并扩全
> (触发 / 前置 / 执行 / 数据层 / 验证 / 遗留 / 速查;**判据与命令一字未改,只重新分节**)。
> 适用:上线判定不过(§3-③)或上线后发现严重缺陷时的人工回退。平台**无自动 rollback 路径**,以下全为人工步骤;
> `bootstrap.sh` 自带容器替换(`platform/bootstrap.sh:54` 的 `docker rm -f ainative-community`),
> "停应用容器 + 换镜像重启"由一条重启命令完成,依赖服务(MySQL/Redis/MinIO)**保持不动**。

- [ ] **回滚触发与决策**
  - **触发条件**(任一命中即发起回滚评估):
    - 上线判定不过:`/checkpreload.htm` 非 200,或 §1-③ 前端探针失败
      (含"根路径 200 但 JS 资源缺失"的整站兜底页情形 —— 探针就是为它设的)
    - 关键业务链路不可用:登录 / 工单 / 派发任一主链路在上一版可用、本版不可用
    - §2-③ 闸门 FAIL 且失败原因指向**本次交付物**(环境缺件不算:按前置清单补齐后重跑闸门,不回滚)
    - 数据层出现本次发布引入的不可逆损坏(迁移只进不退 ⇒ 先按 §4-④ 止损,再定回滚)
  - **决策人:发布负责人**(Admin / 当班发布人);CR 与执行器**不自行决定回滚**
  - **回滚 vs 向前修复**:默认优先**向前修复**;仅当①§4-② 前置检查全绿、且②故障可由换回上一版镜像消除时才回滚
  - **回滚的代价**(决策前知悉):既有登录会话立即失效(比不回滚更糟);配置需人工恢复(平台无 restore 接口,§4-④);
    执行器令牌需重签发(§4-⑥);回滚期间业务不可用窗口 ≈ 应用重启时长
  - `SAVE_STATUS=NONE` ⇒ **回滚路径不可用,只能向前修复**(见 §3-③)

- [ ] **回滚前置检查(动手前逐条确认,任一不成立 ⇒ 回 §3-③ 重新判定,不得带病开滚)**
  - 上一版镜像标签在位:`docker images` 能看到 §1-③ 打印的 `SAVED_IMAGE_TAG=` 那个标签(带日期前缀)
  - 上一版 jar 在位:`SAVED_JAR=` 指向的文件存在(用于 §4-⑤ 的审计兜底;镜像标签在位时**一律走镜像回滚**,
    不手工拼 jar 容器 —— 本清单不提供 jar 级回滚的判据)
  - `platform/runtime.env` 在位且**未被删除重建**:master key 必须与上一版一致 ⇒ 库中已加密凭据仍可解密
    (`bootstrap.sh` 在该文件**缺失时会自动生成新密钥** ⇒ 比不回滚更糟)
  - 数据层预案就位:§3-② 的业务表转储件路径 + 校验和已在发布说明登记
    (没有转储件 ⇒ 数据层不可回退,只能回滚应用层并登记已知风险)
  - 决策已留痕:发布说明已登记"进入回滚"与触发条件(§4-⑥)

- [ ] **回滚执行步骤(严格按此顺序)**
  - 顺序:停应用容器 → **关执行器自动升级** → 用上一版镜像重启 → (如需)按备份恢复**配置**(§4-④)→
    **验证回滚后的容器本身**(§4-⑤)→ 通知受影响方(§4-⑥)
  - **关执行器自动升级(必须在重启前做)**:把
    `AINATIVE_RUNTIME_EXECUTOR_AUTO_UPDATE_ENABLED=false` **追加**进 `platform/runtime.env`
    (该文件经 `--env-file` 注入容器;**宿主 `export` 无效**,容器不会继承宿主的普通环境变量),
    再执行下面的重启命令 —— 否则执行器自动升级会把新版本拉回来,回滚在保护层被静默取消
  - 自证判据:`docker exec ainative-community printenv AINATIVE_RUNTIME_EXECUTOR_AUTO_UPDATE_ENABLED`
    期望输出 `false`
  - 重启:`cd platform && BOOTSTRAP_IMAGE="<上一版镜像>" ./bootstrap.sh`(同脚本、同 env,只换镜像)
    - `<上一版镜像>` **原样抄** §1-③ 打印的 `SAVED_IMAGE_TAG=` 值(标签带日期前缀,别自行拼成
      `ainative-community:<上一版 commit>` —— §1-③ 建的标签带日期前缀,拼错取不到)
    - 重启**必须沿用同一份 `platform/runtime.env`**(`--env-file`)⇒ master key 一变,库中已加密凭据**无法解密**
    - 既有登录会话同时失效,比不回滚更糟;`platform/bootstrap.sh` 在该文件**缺失时会自动生成新密钥** ⇒ 回滚前先确认已存在

- [ ] **数据层处置(配置与业务数据,两条线分开)**
  - **配置恢复**(如需):按 §3-① 的云端备份恢复**配置** —— 仅 32 张配置表;工单/评论/派发/会话/产物/审计/用量
    **不在备份内**;平台**无 restore 接口**(只有创建/列表/下载三个端点)⇒ 需按 §3-② 的预案人工导入,
    导入后抽查关键配置可用
  - **业务数据回退**:由 §3-② 的**转储件预案**覆盖(转储命令 + 文件路径 + 校验和 + 恢复验证方式都在发布说明);
    没有转储件 ⇒ **数据层不可回退** ⇒ 只能回滚应用层,并在发布说明登记已知风险
  - **迁移不可逆的前提**:迁移只进不退(§3-②)⇒ 回滚到上一版镜像后,库 schema 仍停留在**新版**;
    上一版应用面对"多出来的表/列"通常不受影响,但**这一点不作判据** —— 必须以 §4-⑤ 的验证结果为准;
    若上一版应用因 schema 差异起不来,**不得删列/删表回退**(会扩大损坏),按 §4-① 转向前修复
  - 迁移后/回滚后的重做动作(两者同源):重新绑定凭据 → 重设机器人回调密钥 → 重新签发执行器令牌(§4-⑥)

- [ ] **回滚后的验证(对被回滚的产物验,不是重跑闸门)**
  - 在回滚后的容器上重跑 §1-③ 的**前端探针**(`/` 含 `<script`、`assets/*.js` 200)
  - **内容级比对:两端都必须是上一版产物** —— §1-④ 比的是**工作区** jar,与被回滚的产物无关
    (工作区 jar 若已被 §2-② 的 `clean` 删掉,还会被误导向"重建新版本")⇒ 从**被回滚的镜像**取 jar,
    与**上一版 commit** 的入库 dist 比:

    > **本段必须在仓库根执行** —— 里面的 `git ls-tree … -- platform/ainative/frontend/dist`
    > 是**仓库根相对**的路径。在别的目录执行 ⇒ 取到**空列表** ⇒ 比对形同虚设
    > (旧文案会打成 `ROLLBACK_NOT_CLEAN_BUILD`,把「根本没比」说成「上一版镜像没清理干净」)——
    > 实测:不在仓库根执行时 extra=89 / missing=0,正是这个假结论;
    > 块内已加守卫:入库 dist 取不到就只报 `ROLLBACK_CHECK_SKIPPED`。

    ```bash
    # 本段整段粘贴执行(含 exit 0,在交互 shell 里会结束当前 shell)
    # 执行前先把下面两个变量的尖括号内容替换成实际值(不替换会语法报错)
    OLD_IMG="ainative-community:<日期>-<上一版 commit>"  # §1-③ 留存的那个标签(形式 = <日期>-<上一版 commit>)
    OLD_COMMIT="<上一版 commit>"                        # 上一版 commit 的短 sha
    case "$OLD_COMMIT$OLD_IMG" in
      *"<"*) echo "ROLLBACK_CHECK_SKIPPED(占位符未替换 —— 请先填 OLD_IMG / OLD_COMMIT 再执行)" ;;
      *)
        rm -f /tmp/old-ainative.jar /tmp/old-jar-static.txt /tmp/old-dist.txt  # 先清上一轮残留,免得被当成本轮结果
        docker rm -f rc-old >/dev/null 2>&1 || true
        # 取不到被回滚的产物 ⇒ 本段【没做比对】⇒ 只能报 SKIPPED,不能报 MISMATCH
        docker create --name rc-old "$OLD_IMG" >/dev/null 2>&1 \
          || { echo "ROLLBACK_CHECK_SKIPPED(上一版镜像不可用: $OLD_IMG)"; exit 0; }
        docker cp rc-old:/app/ainative.jar /tmp/old-ainative.jar >/dev/null 2>&1 \
          || { echo "ROLLBACK_CHECK_SKIPPED(镜像内取不到 jar)"; docker rm -f rc-old >/dev/null 2>&1; exit 0; }
        docker rm -f rc-old >/dev/null
        python3 -m zipfile -l /tmp/old-ainative.jar \
          | tail -n +2 | awk '{print $1}' | grep '^BOOT-INF/classes/static/' | grep -v '/$' \
          | sed 's#^BOOT-INF/classes/static/##' | sort > /tmp/old-jar-static.txt
        [ -s /tmp/old-jar-static.txt ] \
          || { echo "ROLLBACK_CHECK_SKIPPED(jar 内无静态资源,无法比对)"; exit 0; }
        # 入库 dist 列表为空 ⇒ 多半是不在仓库根执行(路径是仓库根相对的)⇒ 本段没得比
        git ls-tree -r --name-only "$OLD_COMMIT" -- platform/ainative/frontend/dist \
          | sed 's#^platform/ainative/frontend/dist/##' | sort > /tmp/old-dist.txt
        [ -s /tmp/old-dist.txt ] \
          || { echo "ROLLBACK_CHECK_SKIPPED(取不到上一版的入库 dist —— 请在仓库根执行本段)"; exit 0; }
        diff /tmp/old-jar-static.txt /tmp/old-dist.txt || true   # 仅供人眼查看差异,不作为判据
        # 分诊:按【集合方向】判定,不要只看 diff 非空就停
        EXTRA=$(comm -23 /tmp/old-jar-static.txt /tmp/old-dist.txt | wc -l)     # 只在 jar 里 ⇒ 旧 chunk 残留
        MISSING=$(comm -13 /tmp/old-jar-static.txt /tmp/old-dist.txt | wc -l)   # 只在 dist 里 ⇒ 真缺资源
        echo "extra=$EXTRA missing=$MISSING"
        if [ "$EXTRA" -eq 0 ] && [ "$MISSING" -eq 0 ]; then
          echo ROLLBACK_DIST_MATCH
        elif [ "$MISSING" -eq 0 ]; then
          echo "ROLLBACK_NOT_CLEAN_BUILD(旧 chunk 残留,登记后可继续回滚)"
        else
          echo "ROLLBACK_DIST_MISMATCH(真不一致,登记并停止)"
        fi ;;
    esac
    ```

    **OLD_IMG 的值**:§1-③ 留存成功后会打印一行 `SAVED_IMAGE_TAG=ainative-community:<日期>-<上一版 commit>`
    ⇒ **直接复制那一行等号后面的值**;自行拼 `ainative-community:<上一版 commit>` 会取不到镜像
    (§1-③ 建的标签带日期前缀)⇒ 取不到时本段只报 `ROLLBACK_CHECK_SKIPPED`,**不报 MISMATCH**
    (实测:镜像名写错时若不设守卫,会打成 `ROLLBACK_DIST_MISMATCH(真不一致,登记并停止)`,把"没取到产物"误报成"回滚产物有问题")

    **前提**:本判据成立的前提是**上一版镜像按 §1-③ 构建**(含 `rm -rf platform/ainative/target/classes/static`);
    `platform/build.sh:57` 自身**不清** `target/classes/static`(逐次 `cp -R` 累积)⇒
    **上一版若不是在清理后构建的机器上构建的,`diff` 非空不代表回滚产物有误**
    (实测:未清理构建的镜像 jar 静态 **637** 条 vs 入库 dist **89** 条,多出 **548** 条旧 chunk 残留)
  - **判据(按集合方向分诊,不是「diff 非空就停」)**:
    - 打印 `ROLLBACK_DIST_MATCH` ⇒ 一致,继续
    - 打印 `ROLLBACK_NOT_CLEAN_BUILD` ⇒ **上一版镜像不是清理后构建**(旧 chunk 残留)⇒ **登记后可继续回滚**,不必停止
    - 打印 `ROLLBACK_DIST_MISMATCH` ⇒ **真不一致**(入库 dist 有资源不在回滚产物里)⇒ 登记并停止
    - 打印 `ROLLBACK_CHECK_SKIPPED` ⇒ **本段根本没做比对**(占位符没替换 / 上一版镜像不可用 / 镜像内取不到 jar /
      jar 内无静态资源 / 取不到上一版的入库 dist)⇒ **既不是一致也不是不一致**,这次不算判据;
      先按 §1-③ 确认上一版镜像是否留存成功、确认**在仓库根执行**,再重跑本段。
      **不得把 SKIPPED 当成 MISMATCH 去叫停回滚**
  - 比对前会先 `rm -f /tmp/old-ainative.jar` 等三个临时文件 ⇒ 上一轮的残留不会被当成本轮结果
  - 闸门若要跑,**必须在上一版 commit 的源码树上跑**;且闸门验的是
    `ainative-community-e2e:<runId>`、**不是** `ainative-community:<旧标签>` ⇒ 闸门 PASS 不证明回滚后的产物

- [ ] **回滚后遗留动作(验证过了 ≠ 回滚做完了)**
  - **没有自动 rollback 路径**,回滚就是上述人工步骤;回滚后同样要重签发执行器令牌
    (与 §3-② 迁移后的重做动作同源:重新绑定凭据 → 重设机器人回调密钥 → 重新签发执行器令牌)
  - 通知受影响方:回滚窗口、影响范围、当前对外版本号(回到上一版 commit)
  - 发布说明登记回滚事件:触发条件、决策人、`SAVE_STATUS`、§4-⑤ 验证结果(`ROLLBACK_*` 四态之一)、遗留风险
  - 复盘登记:把本次回滚的根因登记到团队缺陷台账,再排热修窗口

- [ ] **回滚速查表(值班一页纸;细节以对应小节为准)**
  | 阶段 | 动作 | 命令 / 依据 | 通过判据 |
  |---|---|---|---|
  | 决策 | 触发评估 | §4-① 触发条件任一命中 | 发布负责人拍板:回滚 / 向前修复 |
  | 前置 | 素材与密钥 | §4-② 逐条确认 | 全绿(且 `SAVE_STATUS` 非 `NONE`) |
  | 停升级 | 关执行器自动升级 | `AUTO_UPDATE_ENABLED=false` 追加进 `platform/runtime.env`(全名见 §4-③) | `printenv` 输出 `false` |
  | 重启 | 换上一版镜像 | `BOOTSTRAP_IMAGE="<SAVED_IMAGE_TAG 值>" ./bootstrap.sh`(§4-③) | `/checkpreload.htm` 返回 200 |
  | 数据 | 配置恢复 | §4-④(§3-① 备份人工导入,无 restore 接口) | 关键配置抽查可用 |
  | 验证 | 前端探针 | §1-③ 探针 4 步 | 打印 `PROBE_OK` |
  | 验证 | 内容级比对 | §4-⑤ bash 块(仓库根执行) | `ROLLBACK_DIST_MATCH`;`NOT_CLEAN_BUILD` 登记后可继续 |
  | 收尾 | 遗留动作 | §4-⑥ | 令牌重签发 + 发布说明登记完成 |

## 已知限制(不作为绿灯判据)

- 无 CI、无 tag、无自动化回滚脚本:以上门禁全为人工执行,结果需随发布说明留档。
- 前端 vitest 在 Node ≥24 下于 setup 阶段**342/342 文件全崩**(`storage.setItem is not a function`),
  套件无法执行,**不作为发布判据**;前端门禁以 `npm run build` 为准。
- 加 `--localstorage-file` 绕过 setup 后仍有 **9~18 个失败文件 / 14~26 个失败用例**(存量 flaky,随运行波动);
  详见 `docs/minimax-check.md` 缺口 G-4。
- `platform/build.sh` **不清 `target/`**(前端静态资源逐次累积)且构建阶段跳过测试 ⇒ 构建绿不能代替
  §1-④ 的内容级比对,测试须单独执行(§2)。
- `platform/ainative/target/` 会被挂载进容器,残留产物或并发构建会把它改写成半成品 ⇒ Maven 增量编译
  会漏掉合成内部类,§2-② 必须带 `clean`,否则会假红(实测 4 个 `NoClassDefFoundError`)。
- 备份不覆盖工单等业务表;数据库迁移只进不退;平台无 restore 接口。
- 闸门宿主引导仍需 JDK 21 + Maven(容器化只覆盖应用构建与 Java 测试),缺件时闸门跑不起来,须在发布机上备齐。
- 闸门另需 Docker Buildx(闸门用 `docker build --platform linux/amd64`);
  **交付路径只需 docker + Compose v2,不依赖 Buildx**。
- 跨机执行器下载任务包受 MinIO 预签名地址限制(BL-11),发布到多机环境前先确认对象存储 endpoint 可达。

**变更记录**

- 2026-11-04 v1.9:工单 #10016(BUG「缺少回滚章节」)增补 —— 新增顶层「## 4. 回滚(发布回退 Runbook)」,
  原 §3-③「回滚执行与验证」整体迁入(§4-⑤ 的验证判据、bash 块与四态分诊**逐字保留,仅章节定位与缩进变化**);
  §3 更名「上线」,§3-③ 改为「上线后判定与回滚入口」(触发判定 + `SAVE_STATUS` ⇒ 能否回滚)。
  §4 按回滚时序扩为 7 条:触发与决策 / 前置检查 / 执行步骤 / 数据层处置 / 回滚后验证 / 遗留动作 / 速查表。
  检查项 10 → **17 条**(构建 4 / 测试 3 / 上线 3 / 回滚 7);此前各条目所称「10 条」为**当版史实**,不改写。
  §1-③ 三处 `SAVED_IMAGE_TAG` 用途的指向由 §3-③ 改为 §4-③;头部新增**回滚入口**一行。
  顺带核销 CR 第 6 轮(comment 10036)4 条 P3:**R-56**(v1.8 条目首句「D-02 不改正文」与条目末尾
  「补一条诊断说明」自相矛盾 ⇒ 首句改为与实际一致);**R-59**(§2-② OOM 重跑命令行 148 字符超 ≤120 约定
  ⇒ 由行内代码改为围栏块 + `\` 续行,命令语义不变,`bash -n` 通过);**R-57**(处置:新章以表格控体量、
  本条目只写要点;《变更记录》**不外置** —— 外置会破坏已核销判据的指纹链);**R-58**(属交接包元数据、
  不在本交付物内 ⇒ 自本轮起交接包只引用 sha256 + 行数,不再声明字节数)。
  自检脚本(全部围栏 `bash -n`、顶层条目数、源码引用回指、已核销判据锚点、§4 结构断言)全过,
  留档于交付补丁随附的 `deliverables/logs/`。

- 2026-11-04 v1.8:返工第 7 次,核销 CR 第 5 轮(comment 10032)的 **R-53 / R-54 / R-55** 三条正文缺陷;
  **D-02** 属证据侧缺陷(§2-② 原文命令缺「成功日志」留档)⇒ 正文只补**诊断说明、判据未改**,成功日志另行留档。
  检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
  **R-53(P2)**:§1-③ 的留存块把「存镜像」与「存 jar」用 `docker tag … && cp …` **串成一步**
  ⇒ 前者失败则后者**根本不执行**;反向情形(tag 成功、`cp` 失败)下**镜像标签其实已建好**,
  文案却统一写「未留存任何产物」且**不打印 `SAVED_IMAGE_TAG=`**
  ⇒ 上一轮跑过 §2-② 的 `clean`(删 `target/`)时,操作者会把**已留存的镜像**误登记为
  「本次发布无上一版产物,回滚路径不可用」;若在 §1-③ 构建后重试,标签会指向**新镜像**,
  §3-③ 拿它与上一版 commit 的入库 dist 比对 ⇒ 可能打出 `ROLLBACK_DIST_MISMATCH` ⇒ **误停一次正确的回滚**。
  修正:①两个动作**各自独立执行、分别报告**,一个失败**不短路**另一个;②`SAVED_IMAGE_TAG=` / `SAVED_JAR=`
  **只在该项成功时打印**,失败文案按项区分(镜像留存失败 / jar 留存失败);
  ③块末新增 `SAVE_STATUS=BOTH / PARTIAL / NONE` 汇总行,**只有 `NONE`(两项皆无)才是「无上一版」**,
  `PARTIAL` 下已成功的那项**照常登记使用**,不得写成「回滚路径不可用」。
  **R-54(P3)**:§2-③ 声明「以下命令在 `platform/ainative` 下执行」,其可选 UI 回归项写
  `python3 scripts/ui_regression_manual.py` ⇒ 解析成 `platform/ainative/scripts/ui_regression_manual.py`,
  该文件**不存在**(脚本真实位置是**仓库根** `scripts/`)⇒ 照抄即 `No such file or directory`,
  操作者会误以为脚本已废弃而**放弃唯一的前端人工回归闸门**。修正:该行写明**在仓库根执行**,
  并注明脚本位置与「§2-③ 其余命令不在那里」。
  **R-55(P3)**:前置把 Compose 限定为「插件形态」并称独立二进制「不等价」,把**版本问题**表述成**形态问题**
  ⇒ 只装了独立 `docker-compose` v2+ 的常见发布机被**误判为缺件** ⇒ 闸门与 `bootstrap.sh` 跑不了,
  或被迫手工起依赖(**绕过 healthcheck 等待与种子导入**)。实测独立二进制 v5.1.0 的 `up --help` 含 `--wait`
  ⇒ **能力等价**,缺的只是**命令名**。修正:前置拆成两条可独立判定的要求 ——
  ①**能力**:Compose ≥ v2(须支持 `up -d --wait`,v1 老二进制不满足);
  ②**命令名**:`docker compose` 必须可用(装官方插件,**或**把 v2+ 独立二进制放到
  `~/.docker/cli-plugins/docker-compose` 使其成为插件);并同步改写 §1-③ 里「宿主缺 `docker compose` 插件时」的旧说法。
  另按 CR 的 U6 顺手订正:`docker-compose.yml:16` ⇒ `docker-compose.yml:17`(硬编码口令的 `test:` 实际在 :17)。
  另按 **D-02** 的失败场景在 §2-② 补一条**诊断说明**(判据**未改**):原文命令在容器可用内存不足时,
  surefire 派生的测试 JVM 会被 **OOM 杀掉** ⇒ 输出 `Process Exit Code: 137` +
  `The forked VM terminated without properly saying goodbye` + `Crashed tests: …`
  (dump / dumpstream 落在 `platform/ainative/target/surefire-reports/`)——
  这是**进程被杀**、**不是用例失败** ⇒ 给出加大堆上限后的重跑命令(判据仍是
  `BUILD SUCCESS` + `Failures: 0, Errors: 0` + 退出码 0)与「**成功日志必须与命令一并留档**」的要求。

- 2026-11-04 v1.7:返工第 6 次,核销本轮自查新发现 **R-48 / R-49 / R-51 / R-52**(R-50 经实测**证伪**,未改)——
  **R-48(P2)**:§1-④ 的漂移处置里,「**还原**」分支写「漂移**依然存在**,须登记、不得当作绿灯」,
  与同句对漂移的定义(`git status` 非空)**自相矛盾**:实测 `git checkout -- platform/ainative/frontend/dist`
  之后 `git status --porcelain -- platform/ainative/frontend/dist` **变空**、文件内容回到入库版本
  ⇒ 漂移是**消失**的;真正残留的是「已构建的 jar / 镜像里可能仍是重建过的 dist」,处置是**重跑 §1-④
  内容级比对**(`DIST_MATCH` ⇒ 绿灯;非空 ⇒ 重跑 §1-③ 先清理再构建),原文案会让人以为还原后还得登记风险。
  **R-49(P2)**:§3-③ 的回滚比对块在**取不到被回滚的产物**时(`docker create` 失败 = 镜像名写错/镜像已不在)
  不 fail-closed ⇒ `docker cp` 失败、jar 为空,却仍按集合方向打出 `ROLLBACK_DIST_MISMATCH(真不一致,登记并停止)`
  ⇒ 把「没取到产物」误报成「回滚产物有问题」,**误停一次正确的回滚**(实测复现);且块内示例镜像名写成
  `ainative-community:<上一版 commit>`,而 §1-③ 实际建的是带日期前缀的 `ainative-community:<日期>-<上一版 commit>`
  ⇒ 照抄示例必然取不到镜像。修正:①取产物失败一律只报 `ROLLBACK_CHECK_SKIPPED`(占位符未替换 / 镜像不可用 /
  镜像内取不到 jar / jar 内无静态资源),**不得当成不一致去叫停回滚**;②`OLD_IMG` 改为与 §1-③ 同款示例,
  并让 §1-③ 留存成功后打印 `SAVED_IMAGE_TAG=…` / `SAVED_JAR=…` 供原样抄;③判据清单同步说明 SKIPPED 的五种来源。
  **R-51(P3)**:§3-① 的两条自证判据(MySQL 新旧口令、MinIO 新旧凭据)只给了**宿主客户端**写法
  (`mysql` / `mc`),而这两样在发布机上不一定存在,前置清单也没列 ⇒ 补容器内等价命令
  (`docker exec platform-mysql-1 mysql …`;MinIO 侧实测发现镜像里的 `local` 别名**没有凭据**
  (`mc alias list` 里 AccessKey/SecretKey 为空、`mc ls local` 直接 Access Denied),
  故改为在容器内用当前 env 的凭据自建别名 `mc alias set cur …$MINIO_ROOT_USER… && mc ls cur`,
  旧凭据则用 `mc alias set old … minioadmin minioadmin` 验证被拒)。
  **R-52(P1)**:§1-③ 的留存块与 §3-③ 的比对块都用**仓库根相对**路径
  (`platform/ainative/target/ainative.jar` / `git ls-tree … -- platform/ainative/frontend/dist`),
  但两处都**没有声明执行目录** ⇒ 实测在仓库根之外执行时:①§1-③ 的 `cp` 失败 ⇒ **jar 静默没留存**
  (而文案把失败解释成「无上一版」)⇒ 回滚素材丢失;②§3-③ 的 `git ls-tree` 取到**空列表** ⇒ 比对根本没发生,
  却打出 `ROLLBACK_NOT_CLEAN_BUILD(旧 chunk 残留,登记后可继续回滚)`(实测 extra=89 / missing=0)——
  把「没比」说成「上一版镜像没清理干净」。修正:①两段都写明**在仓库根执行**;②§1-③ 的留存改为
  `docker tag` 与 `cp` **都成功才**打印 `SAVED_IMAGE_TAG` / `SAVED_JAR`,失败只打印 STOP
  (不用 `exit`,免得关掉操作者的终端);③§3-③ 增加入库 dist 列表为空 ⇒ 只报 `ROLLBACK_CHECK_SKIPPED` 的守卫。
  (**本条的②已被 v1.8 取代** —— 见 v1.8 的 R-53:两个动作改为**分别执行、分别报告**)
  **R-50(P1 假设)已证伪**:曾怀疑「改 MySQL root 口令会让 compose healthcheck 失败 ⇒
  `bootstrap.sh` 的 `docker compose up -d --wait` 起不来栈」(healthcheck 里硬编码了 `-pchange-root-me`),
  实测**不成立** —— `mysqladmin ping` 在 Access denied 时**仍返回 0**(MySQL 官方语义:服务器在跑就返回 0),
  加固后容器照常 healthy;只是探针从此不再验证 root 口令,已在 §3-① 附注说明。
  检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.6:返工第 5 次(自查补正),核销自查新发现 **R-47** —— 文档里 §1-③ / §3-③ 两个
  bash 块的占位符写成裸的 `<日期>` / `<上一版 commit>` / `<留存目录>`,而 `<` 在 shell 里是**重定向符**
  ⇒ 操作者原样粘贴会直接 `syntax error near unexpected token 'newline'`,**一条命令都执行不了**
  (实测:8 个 bash 围栏中 2 个 `bash -n` 不过)。危害不是"报错"而是**静默跳过**:§1-③ 是
  **发布动作第一步(留存上一版镜像与 jar)**,它的原文案又把"命令报错"解释成"无上一版 → 本步跳过"
  ⇒ 操作者照做即跳过留存 ⇒ 旧镜像被 `build.sh` 覆盖 ⇒ **回滚路径丢失**,而这正是清单要防的事。
  修正:① 占位符全部加引号并改由变量承载,块内用 `case` 守卫,**未替换 ⇒ 只打印 STOP、不做任何改动**;
  ② §1-③ 的文案把「占位符没替换(STOP,必须填值)」与「确实无上一版产物(可跳过并登记)」拆成两种;
  ③ §3-③ 同样加 `case` 守卫,未替换时输出 `ROLLBACK_CHECK_SKIPPED` 而非假判据,并在比对前
  `rm -f` 三个 `/tmp` 临时文件(上一轮残留曾会被当成本轮结果)。检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.5:返工第 5 次,核销评审 R-43~R-46 —— §3-① 把「改默认口令」重写为**两处载体必须同步**
  (compose 的 `MYSQL_PASSWORD`/`MYSQL_ROOT_PASSWORD` 与 `platform/runtime.env` 的
  `SPRING_DATASOURCE_PASSWORD`;Redis 同理,且 compose 当前**根本没有** `requirepass`),补
  **「已有数据卷时改 `environment` 不会改掉库里的口令」**的前提(实测:同一具名卷先以 `change-me`
  初始化、再以新口令重建容器 ⇒ 旧口令仍可连、新口令 `ERROR 1045 Access denied`),补不丢数据的改法
  (库内 `ALTER USER` 或按 §3-② 转储件重建卷)与**旧口令失败**的自证判据;§3-① 补 **MinIO** 加固
  (默认口令 + 全网卡暴露,**项目备份包就在它里面**),并实测确认 MinIO 根凭据**与 MySQL 不同** ——
  已初始化的 `minio-data` 卷上改 env **即生效**(同卷换凭据重起 ⇒ 新凭据可列举、旧凭据报
  `InvalidAccessKeyId`),不需要 ALTER 类手段;§3-③ 补**「上一版镜像按 §1-③ 清理后构建」的前提声明**
  与**按集合方向的分诊**(jar 多出旧 chunk 残留 ⇒ `ROLLBACK_NOT_CLEAN_BUILD`,登记后可继续回滚;
  dist 有资源不在 jar 里 ⇒ `ROLLBACK_DIST_MISMATCH`,才是真不一致)—— 原文案只看 `diff` 非空就停,
  会对一次正确的回滚误报;§3-② 补**迁移目录完整路径** `platform/ainative/docs/migration/`
  与命名契约见同目录 `README.md`。检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.4:返工第 4 次,核销本轮自查发现的 R-39~R-42 —— §3-③ 的回滚后**内容级比对**改为
  「两端都取上一版产物」:从**被回滚的镜像**里 `docker cp` 出 jar,与**上一版 commit** 的入库 dist 比对
  (原文案指向 §1-④ 的**工作区** jar,jar 被 §2-② 的 `clean` 删掉时会把人导向重建新版本,
  未删时则拿新版本 jar 比对而假绿),新增 `ROLLBACK_DIST_MATCH` 判据;§2-① 的第 3 个 `tests/` 路径
  订正为 `platform/ainative/scripts/tests`(原文案沿用了评审稿的笔误,目录不存在);
  §1-③ 的「留存上一版」补「无上一版(首次发布 / 干净发布机)⇒ 跳过并登记『回滚路径不可用』」分支;
  §2-③ 补 `--status` 两种返回各自的**退出码与含义**(无遗留 run 时退出码也是 1,原文案未给退出码,
  易被误判为闸门被阻断)。检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.3:返工第 3 次,核销评审 R-16~R-36 —— 前置区分「闸门 / 交付路径」的 Buildx 归属、
  补 `nodejs.org`/`registry.npmjs.org` 网络前置、改正 `--no-install` 为「全部 9 项且在第一个缺件处中止」、
  给依赖预检**成对补 `--clean-up`**(原样执行会让闸门必然 FATAL);§1-② 剔除 `runtime.env` 的
  `AINATIVE_VERSION`(生成产物、非版本源);§1-③ 补「发布动作第一步留存上一版镜像与 jar」、
  前端探针改 fail-closed(取不到 JS 资源名即失败)、备用 `docker run` 路径补同一套探针、
  补 `platform/.dockerignore` 的存在性要求;§1-④ 补 `npm ci`、改用零新增依赖的
  `python3 -m zipfile` 列 jar、补存在性断言与「jar 不存在 / 工具缺失 / 真的不一致」三型分诊;
  §2-① 改绝对路径并补套件身份判据;§2-② 补执行基准目录;§2-③ 补跑前 `--status`、跑后 `--clean-up`
  收尾与留档路径、`--stop` 不删指针的说明、`dist` 漂移复查、两处可选项的前置;
  §3-① 补默认口令与端口绑定加固、补「无 restore 接口」披露;§3-② 补业务表转储件要求与**迁移演练**、
  明写「闸门不覆盖迁移」;§3-③ 改为「恢复**配置**」、回滚后改为**对回滚后的容器验**、
  写明关自动升级的载体是 `platform/runtime.env`(宿主 `export` 无效)并补 `printenv` 自证判据。
  另本步自查发现并修正两条:新增 `platform/.dockerignore`(排除 `target` 内容但**保留 jar**,
  否则 `COPY` 找不到 jar 会打断构建)与恢复 R-07 的 ≤120 字符排版约定。
  检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.2:返工第 2 次,核销评审 R-08~R-14 —— §2-③ 判据改「退出码 0 + `VERDICT=PASS` +
  `result.json` 的 `verdict=PASS`」,并标注 `LIFECYCLE|END|PASS` 只是 `--start` 的启动完成标志;
  §1-③ 补「先清理 `target/classes/static` 再构建」与交付镜像前端探针(`/` 含 `<script`、`assets/*.js`);
  §1-④ 补「镜像内静态资源 == 入库 dist」的内容级判据与漂移两分支处置;前置区分闸门/前端构建依赖、
  修正 Node 与 `python:3.12-alpine` 归属、「只读预检」改「依赖预检」并写明仍会走完启动流程;
  备用 `docker run` 路径补执行基准与 `JAVA_OPTS`;vitest 崩溃量级与存量失败拆句。
  另本步新发现并修正 R-15:§2-② 的 Maven 命令补 `clean`(实测残留/半成品 `target/` 会造成 4 个
  `NoClassDefFoundError` 假红),并补 `target/` 会被并发构建改写的风险说明。
  检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.1:返工修订,核销评审 R-01~R-07 —— 补"闸门镜像 ≠ 交付镜像"与交付镜像冒烟、补闸门前置依赖、
  补 `settings.xml` 前置、回滚沿用运行期密钥、判据改不变量、`--with-runtime` 挂载点、前端命令去重、排版改三行式。
  检查项仍为 10 条(构建 4 / 测试 3 / 上线与回滚 3)。
- 2026-11-04 v1.0:首次发布,共 10 条检查项(构建 4 / 测试 3 / 上线与回滚 3)。
