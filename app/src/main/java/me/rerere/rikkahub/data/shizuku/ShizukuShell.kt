package me.rerere.rikkahub.data.shizuku

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 外壳（2026-09-19）。
 *
 * ## 这是什么
 *
 * Shizuku 借一次「无线调试 / root」把服务端跑成 **shell(uid 2000)**，再通过 binder
 * 把这份权限借给应用。拿到之后 `pm grant` / `appops set` / `settings put` / `am` /
 * `cmd` / `dumpsys` 这些平时只能靠 adb 敲的命令，应用自己就能执行。
 *
 * ## 为什么需要它
 *
 * workspace 里那个 shell 是应用进程的子进程（uid 就是应用 uid），既看不见 `/system`
 * 也摸不到 binder —— 想从 shell 侧直接要 ADB 权限是死路。唯一合法入口就是应用
 * 自己通过 Shizuku 拿。
 *
 * ## 它不负责「跑」
 *
 * 这里只管**开进程**；读流、超时、收尾全在 [ShellRunner] 里做，因为本地模式
 * （不走 Shizuku）要走同一套收尾逻辑，没必要写两份。
 *
 * ## 前置条件
 *
 * 1. 设备装了 Shizuku 且已启动（无线配对 / root）
 * 2. 本应用在 Shizuku 里被授权
 *
 * 两条缺一，[newProcess] 返回 null，调用方去问 [isBinderAlive] / [isPermissionGranted]
 * 拿具体原因。
 */
class ShizukuShell {

    /** Shizuku 服务进程活着吗（跟「有没有授权」是两件事） */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本应用拿到 Shizuku 授权了吗 */
    fun isPermissionGranted(): Boolean {
        if (!isBinderAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 能干活吗：binder 通 + 已授权 */
    fun isReady(): Boolean = isBinderAlive() && isPermissionGranted()

    /**
     * 以 shell(uid 2000) 身份起一个 `sh -c <command>` 进程。
     *
     * 用 `sh -c` 包一层，所以管道、重定向、`&&` 都能用。
     *
     * @return 起不来时返回 null（Shizuku 没跑 / 没授权 / binder 抛异常），
     *         具体原因查 [isBinderAlive] 和 [isPermissionGranted]。
     */
    fun newProcess(command: String): Process? {
        if (!isBinderAlive()) {
            Log.w(TAG, "newProcess: shizuku binder not alive")
            return null
        }
        if (!isPermissionGranted()) {
            Log.w(TAG, "newProcess: shizuku permission not granted")
            return null
        }
        return runCatching {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        }.onFailure {
            Log.w(TAG, "newProcess failed", it)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ShizukuShell"

        /** Shizuku 权限请求的 requestCode，随便挑一个正的 */
        const val PERMISSION_REQUEST_CODE = 0x5A1F
    }
}
