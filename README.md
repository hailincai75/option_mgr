# Option Manager

个人期权交易管理系统（Spring Boot 后端 + React 前端），用 Claude Code 的四个 subagent 开发：
pm / backend-dev / frontend-dev / qa。规则和流程见 CLAUDE.md。

## 日常使用
- **启动**：双击 `start.cmd`（或运行 `.\start.ps1`）。它会自动找到 JDK 21，分别在两个窗口里启动后端（8080）和前端（5173），然后打开浏览器 http://localhost:5173。关闭这两个窗口即停止。
  - 找不到 JDK 21 时，设置环境变量 `OPTIONMGR_JAVA_HOME` 指向 JDK 21 的目录。
  - 跑 E2E 时不要同时开着日常用的后端（E2E 会拒绝运行，以免碰到真实数据）。
- **数据**：全部在 `backend/data/optionmgr.mv.db`（不进 git）。系统没有备份功能，请在**关闭应用后**定期把这个文件复制到别处备份。
- **只在本机使用**：没有登录，不要把 8080/5173 开放到局域网或公网。
- **M1 的限制**：没有到期作废/指派/行权（M2），不能卖出 call（FEAT-011），不支持部分平仓（Q21，预计分批平仓时请把开仓拆成几笔录入），利息杂费用入金/出金加备注记录。

## 开发流程
1. `pm` 写需求（Draft）→ 我审核改为 Approved
2. `backend-dev` 先写 API 契约，再实现后端
3. `frontend-dev` 按契约实现页面
4. 前后端完成 → Implemented
5. `qa` 做 API 集成测试 + E2E → Verified 或 Failed（附 bug）
